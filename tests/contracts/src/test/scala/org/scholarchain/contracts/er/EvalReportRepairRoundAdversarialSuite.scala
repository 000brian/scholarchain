package org.scholarchain.contracts.er

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, NoSuchFileException, Path}

import org.scholarchain.er.eval.{EvalInputs, EvalMain, GoldLabel, GoldSet, Harness, KeyRow}

/** Adversarial tests of the Phase 5 run-2 repair round of the evaluation report (repairs 12 to 19):
  * the LLM-path statement derived only from the run report's LLM stage outcomes, policy 'weights'
  * and run-report 'knownRecallGaps' that are absent or of the wrong type, the three states of the
  * manifest's policy override, run thresholds from the manifest only (including thresholds off the
  * sweep grid), malformed optional JSON naming role and path, and the invariant that no text
  * depends on an adjudicator's name beyond quoting it.
  *
  * Every label, key row, queue row and run artifact is synthetic and built in the test; nothing
  * reads docs/agent-runs or the human gold file.
  */
class EvalReportRepairRoundAdversarialSuite extends munit.FunSuite {

  // ---------------------------------------------------------------------------------------------
  // Synthetic builders

  private def m(i: Int) = f"mention:v1:$i%032x"
  private def c(i: Int) = f"match-candidate:v1:$i%032x"

  private def stratum(path: String, outcome: String) =
    s"path=$path;outcome=$outcome;family=SYNTH;band=far"

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

  private def keyRow(
      i: Int,
      outcome: String,
      path: String = "probabilistic",
      p: Option[Double] = Some(0.5),
      reasons: List[String] = Nil
  ): KeyRow = KeyRow(
    m(2 * i - 1),
    m(2 * i),
    c(i),
    List((stratum(path, outcome), 50.0)),
    outcome,
    path,
    "SYNTH",
    p,
    0.5,
    List("name_exact_match" -> 1.0, "SYNTH_unweighted_feature" -> 0.0),
    Nil,
    reasons,
    List((outcome, path))
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

  /** One deterministic row: the gold set has no labeled llm-path row. */
  private val noLlmRows = List(label(3, "MATCH") -> keyRow(3, "accepted", "deterministic", None))

  /** Two labeled llm-path rows that the adjudicator left unresolved, plus a deterministic row. */
  private val abstainRows = List(
    label(1, "MATCH") -> keyRow(1, "unresolved", "llm", reasons = List("llm-abstain")),
    label(2, "NON_MATCH") -> keyRow(2, "unresolved", "llm", reasons = List("llm-abstain"))
  ) ++ noLlmRows

  private def config(entries: (String, ujson.Value)*): ujson.Obj = ujson.Obj.from(entries)

  private def manifestWith(cfg: ujson.Value): ujson.Obj = ujson.Obj(
    "runId" -> "run:v1:SYNTHETIC",
    "inputRun" -> ujson.Obj(
      "jobName" -> "SYNTHETIC.upstream",
      "runId" -> s"run:v1:${"a" * 32}",
      "snapshotId" -> "SYNTHETIC-snapshot"
    ),
    "config" -> cfg
  )

  private def manifest(
      adjudicator: String = "SYNTHETIC-adjudicator",
      model: String = "SYNTHETIC/model-1",
      thresholds: Option[ujson.Value] = Some(ujson.Obj("accept" -> 0.9, "reject" -> 0.3))
  ): ujson.Obj = manifestWith(
    config(
      List[(String, ujson.Value)](
        "policySha256" -> "SYNTHETIC-sha",
        "policyOverriddenFrom" -> ujson.Null,
        "llmModelId" -> model,
        "adjudicator" -> adjudicator
      ) ++ thresholds.map("thresholds" -> _).toList*
    )
  )

  private def runReport(llm: Option[ujson.Value]): ujson.Obj =
    ujson.Obj.from(
      List[(String, ujson.Value)](
        "inputs" -> ujson.Obj("works" -> 10),
        "knownRecallGaps" -> ujson.Arr()
      ) ++ llm.map("llm" -> _).toList
    )

  private def llmStage(outcomes: ujson.Value): ujson.Obj = ujson.Obj(
    "outcomes" -> outcomes,
    "pairsSentToAdjudicator" -> 5,
    "bandCandidates" -> 5
  )

  private val policyThresholds = ujson.Obj("accept" -> 0.9, "reject" -> 0.3)

  private def policy(weights: Option[ujson.Value]): ujson.Obj = ujson.Obj.from(
    List[(String, ujson.Value)](
      "policyVersion" -> "SYNTHETIC-policy",
      "status" -> "SYNTHETIC",
      "priorLogOdds" -> -1.0,
      "thresholds" -> policyThresholds
    ) ++ weights.map("weights" -> _).toList
  )

  private val oneWeight = ujson.Arr(
    ujson.Obj(
      "feature" -> "name_exact_match",
      "bins" -> ujson.Arr(ujson.Obj("min" -> 0, "max" -> ujson.Null, "weight" -> 1.5)),
      "scaledBySourceReliability" -> false,
      "rationale" -> "SYNTHETIC"
    )
  )

  private def statement(met: ujson.Value): String =
    met("researcherResolution")("llmPath")("statement").str

  private def md(met: ujson.Obj): String = Harness.markdown(met)

  private def lineStarting(text: String, prefix: String): String =
    text.linesIterator.find(_.startsWith(prefix)).getOrElse(fail(s"no line '$prefix' in:\n$text"))

  private val NoDecisionClaim = "records no accepted or rejected pair"
  private val DecisionClaim = "records accepted or rejected pairs"
  private val NotGivenClaim =
    "is not available (run report LLM stage outcomes not given)"

  // ---------------------------------------------------------------------------------------------
  // repair-12: run outcomes record decisions

  test("repair-12: accepted>0 with zero labeled llm rows states run decisions, never 'none'") {
    val met = eval(
      noLlmRows,
      EvalInputs(
        manifest = Some(manifest()),
        runReport = Some(runReport(Some(llmStage(ujson.Obj("accepted" -> 3, "rejected" -> 2)))))
      )
    )
    val st = statement(met)
    assert(st.startsWith("LLM-adjudicated metrics are empty: the gold set has no labeled"), st)
    assert(st.contains(s"$DecisionClaim (outcomes: accepted 3, rejected 2)"), st)
    assert(st.contains("but none of them is a labeled llm-path row"), st)
    assert(!st.contains(NoDecisionClaim), st)
    assert(!st.contains(NotGivenClaim), st)
    assert(!st.contains("stayed unresolved"), st)
    // no labeled llm row: the coverage tail must not appear
    assert(!st.contains("The path llm row shows only"), st)
    // the Markdown carries the same statement and the run LLM stage counts
    val text = md(met)
    assert(text.contains(st), text)
    assert(text.contains("Run LLM stage: accepted 3, rejected 2"), text)
  }

  test("repair-12: only rejected>0 (accepted 0) also counts as the adjudicator deciding") {
    val met = eval(
      abstainRows,
      EvalInputs(runReport =
        Some(runReport(Some(llmStage(ujson.Obj("accepted" -> 0, "rejected" -> 1)))))
      )
    )
    val st = statement(met)
    assert(st.contains(DecisionClaim), st)
    assert(!st.contains(NoDecisionClaim), st)
    // labeled llm rows exist: their outcomes and the coverage tail are stated
    assert(st.contains("none of the 2 labeled llm-path rows was accepted or rejected"), st)
    assert(st.contains("The path llm row shows only their coverage and unresolved rate."), st)
  }

  test("repair-12: run outcomes with no accept or reject state that and quote the outcomes") {
    val met = eval(
      abstainRows,
      EvalInputs(runReport =
        Some(
          runReport(
            Some(llmStage(ujson.Obj("accepted" -> 0, "rejected" -> 0, "unresolved" -> 4)))
          )
        )
      )
    )
    val st = statement(met)
    assert(st.contains(s"$NoDecisionClaim (outcomes: accepted 0, rejected 0, unresolved 4)"), st)
    assert(!st.contains(DecisionClaim), st)
    assert(!st.contains(NotGivenClaim), st)
  }

  test("repair-12: labeled llm rows that were decided are counted whatever the run report says") {
    val rows = List(
      label(1, "MATCH") -> keyRow(1, "accepted", "llm"),
      label(2, "NON_MATCH") -> keyRow(2, "unresolved", "llm", reasons = List("llm-abstain"))
    )
    val met = eval(
      rows,
      EvalInputs(runReport = Some(runReport(Some(llmStage(ujson.Obj("unresolved" -> 9))))))
    )
    val st = statement(met)
    assert(st.startsWith("1 of 2 labeled llm-path rows were accepted or rejected"), st)
    assert(!st.contains("empty"), st)
  }

  // ---------------------------------------------------------------------------------------------
  // repair-13: run outcomes not given

  test("repair-13: run report without llm, llm without outcomes, or non-object outcomes") {
    val variants: List[(String, Option[ujson.Value])] = List(
      "no run report" -> None,
      "run report without llm" -> Some(runReport(None)),
      "llm without outcomes" -> Some(
        runReport(Some(ujson.Obj("pairsSentToAdjudicator" -> 5, "bandCandidates" -> 5)))
      ),
      "outcomes is an array" -> Some(runReport(Some(llmStage(ujson.Arr(3, 2))))),
      "outcomes is a string" -> Some(runReport(Some(llmStage(ujson.Str("accepted 3"))))),
      "outcomes is null" -> Some(runReport(Some(llmStage(ujson.Null)))),
      "llm is a number" -> Some(ujson.Obj("llm" -> 5))
    )
    for ((name, rr) <- variants; rows <- List(noLlmRows, abstainRows)) {
      val st = statement(eval(rows, EvalInputs(manifest = Some(manifest()), runReport = rr)))
      assert(st.contains(NotGivenClaim), s"$name: $st")
      assert(!st.contains(NoDecisionClaim), s"$name: $st")
      assert(!st.contains(DecisionClaim), s"$name: $st")
    }
  }

  test("repair-13: without a manifest the adjudicator and model are not available, not invented") {
    val met = eval(abstainRows)
    val st = statement(met)
    assert(
      st.contains(
        "The run used adjudicator 'not available (input not given)' with model id 'not available (input not given)'."
      ),
      st
    )
    assertEquals(met("researcherResolution")("llmPath")("adjudicator"), ujson.Null)
    assertEquals(met("researcherResolution")("llmPath")("llmModelId"), ujson.Null)
    assertEquals(met("researcherResolution")("llmPath")("runLlmStage"), ujson.Null)
  }

  test("FINDING repair-12: a non-numeric outcome count is not read as zero decisions") {
    // the run report's accepted count is the string "3": the statement must not claim that the
    // run recorded no accepted pair while quoting "accepted 3"
    val met = eval(
      abstainRows,
      EvalInputs(runReport = Some(runReport(Some(llmStage(ujson.Obj("accepted" -> "3"))))))
    )
    val st = statement(met)
    assert(
      !st.contains(NoDecisionClaim),
      s"a non-numeric accepted count was defaulted to zero, giving a self-contradicting claim: $st"
    )
  }

  // ---------------------------------------------------------------------------------------------
  // repairs 12, 13 and 19: no text asserts behaviour of an adjudicator from its name

  test("repairs 12/13/19: every text is independent of the adjudicator's name except the quote") {
    val names = List(
      "always-abstain",
      "always-accept",
      "SYNTHETIC-never-decides",
      "SYNTHETIC-live-bedrock",
      "none"
    )
    val runReports: List[(String, Option[ujson.Value])] = List(
      "no run report" -> None,
      "no outcomes" -> Some(runReport(None)),
      "no decisions" -> Some(runReport(Some(llmStage(ujson.Obj("unresolved" -> 5))))),
      "decisions" -> Some(runReport(Some(llmStage(ujson.Obj("accepted" -> 3, "rejected" -> 2)))))
    )
    val Placeholder = "<<ADJUDICATOR>>"
    for (
      (rrName, rr) <- runReports;
      (rowsName, rows) <- List("none" -> noLlmRows, "abstain" -> abstainRows)
    ) {
      val rendered = names.map { n =>
        val met = eval(rows, EvalInputs(manifest = Some(manifest(adjudicator = n)), runReport = rr))
        // quote positions only: the run-context line and the statement's quoted name
        val text = md(met)
          .replace(s"adjudicator $n,", s"adjudicator $Placeholder,")
          .replace(s"adjudicator '$n'", s"adjudicator '$Placeholder'")
        (n, text, met("researcherResolution")("llmPath")("adjudicator").str)
      }
      rendered.foreach { case (n, _, recorded) => assertEquals(recorded, n) }
      val distinct = rendered.map(_._2).distinct
      assertEquals(
        distinct.size,
        1,
        s"$rrName/$rowsName: the Markdown differs by adjudicator name beyond quoting it"
      )
      val text = distinct.head
      List(
        "never accepts",
        "never decides",
        "always abstains",
        "no live model",
        "is called by this adjudicator",
        "every LLM band pair stays unresolved"
      ).foreach(p => assert(!text.contains(p), s"$rrName/$rowsName: '$p' in Markdown"))
    }
  }

  test("repair-19: the run-context LLM line only quotes manifest values") {
    val met = eval(abstainRows, EvalInputs(manifest = Some(manifest("always-abstain", "none/x"))))
    assertEquals(
      lineStarting(md(met), "- LLM stage:"),
      "- LLM stage: adjudicator always-abstain, model id none/x; prompt not available (sha256 not available); output schema not available (sha256 not available)."
    )
  }

  // ---------------------------------------------------------------------------------------------
  // repair-14: policy weights

  test("repair-14: 'weights' that is not an array is not available, never an empty list") {
    val bad: List[ujson.Value] =
      List(ujson.Obj("name_exact_match" -> 1.5), ujson.Str("SYNTH"), ujson.Num(1), ujson.Null)
    for (w <- bad) {
      val met = eval(abstainRows, EvalInputs(policy = Some(policy(Some(w)))))
      val rc = met("runContext")
      assertEquals(rc("weightedFeatures"), ujson.Null, s"weights $w")
      assertEquals(rc("recordedFeaturesWithoutWeight"), ujson.Null, s"weights $w")
      val text = md(met)
      assert(
        text.contains("Feature definitions not available: the policy file has no 'weights' list."),
        s"weights $w:\n$text"
      )
      assert(!text.contains("| Feature | Bins"), s"weights $w: feature table rendered")
      assert(!text.contains("without a policy weight"), s"weights $w: unweighted list rendered")
      assert(!text.contains("Policy file not given"), s"weights $w: policy was given")
    }
  }

  test("repair-14: an explicit empty 'weights' list is reported as given, not as missing") {
    val met = eval(abstainRows, EvalInputs(policy = Some(policy(Some(ujson.Arr())))))
    val rc = met("runContext")
    assertEquals(rc("weightedFeatures"), ujson.Arr())
    assertEquals(
      rc("recordedFeaturesWithoutWeight").arr.map(_.str).toList,
      List("SYNTH_unweighted_feature", "name_exact_match")
    )
    assert(!md(met).contains("Feature definitions not available"))
  }

  test("repair-14: present weights list only the recorded features that lack a weight") {
    val met = eval(abstainRows, EvalInputs(policy = Some(policy(Some(oneWeight)))))
    assertEquals(
      met("runContext")("recordedFeaturesWithoutWeight").arr.map(_.str).toList,
      List("SYNTH_unweighted_feature")
    )
    assert(md(met).contains("| name_exact_match | 0 to open: 1.5 | false | SYNTHETIC |"))
  }

  // ---------------------------------------------------------------------------------------------
  // repair-15: known recall gaps

  test("repair-15: 'knownRecallGaps' that is not an array is not available, never 'no gaps'") {
    val bad: List[ujson.Value] =
      List(ujson.Obj("id" -> "G1"), ujson.Str("G1"), ujson.Num(0), ujson.Null)
    for (g <- bad) {
      val rr = ujson.Obj("inputs" -> ujson.Obj("works" -> 10), "knownRecallGaps" -> g)
      val met = eval(abstainRows, EvalInputs(runReport = Some(rr)))
      assertEquals(met("knownLimitations")("knownRecallGaps"), ujson.Null, s"gaps $g")
      val text = md(met)
      assert(
        text.contains(
          "- Known recall gaps: not available (the run report has no 'knownRecallGaps' list)."
        ),
        s"gaps $g:\n$text"
      )
      assert(!text.contains("run report not given"), s"gaps $g")
      assert(!text.contains("- Recall gap "), s"gaps $g")
    }
  }

  test("repair-15: recall gaps present are listed with their numbers; empty list is no gap") {
    val rr = ujson.Obj(
      "knownRecallGaps" -> ujson.Arr(
        ujson.Obj("id" -> "SYNTH-G1", "pairs" -> 12, "description" -> "SYNTHETIC gap.")
      )
    )
    val met = eval(abstainRows, EvalInputs(runReport = Some(rr)))
    assert(md(met).contains("- Recall gap SYNTH-G1 (run report): pairs 12. SYNTHETIC gap."))
    val empty =
      eval(abstainRows, EvalInputs(runReport = Some(ujson.Obj("knownRecallGaps" -> ujson.Arr()))))
    assertEquals(empty("knownLimitations")("knownRecallGaps"), ujson.Arr())
    assert(!md(empty).contains("Known recall gaps: not available"))
  }

  // ---------------------------------------------------------------------------------------------
  // repair-16: policy override

  test("repair-16: override absent vs explicit null vs string vs no config vs no manifest") {
    def base = List[(String, ujson.Value)]("policySha256" -> "SYNTHETIC-sha")
    val cases: List[(String, Option[ujson.Value], ujson.Value, ujson.Value, String)] = List(
      (
        "field absent",
        Some(manifestWith(config(base*))),
        ujson.Null,
        ujson.Bool(false),
        "not available (the run manifest does not record it)"
      ),
      (
        "explicit null",
        Some(manifestWith(config((base :+ ("policyOverriddenFrom" -> ujson.Null))*))),
        ujson.Null,
        ujson.Bool(true),
        "none"
      ),
      (
        "string",
        Some(
          manifestWith(
            config((base :+ ("policyOverriddenFrom" -> ujson.Str("SYNTHETIC/policy-v0.json")))*)
          )
        ),
        ujson.Str("SYNTHETIC/policy-v0.json"),
        ujson.Bool(true),
        "SYNTHETIC/policy-v0.json"
      ),
      (
        "manifest without config",
        Some(ujson.Obj("runId" -> "run:v1:SYNTHETIC")),
        ujson.Null,
        ujson.Bool(false),
        "not available (the run manifest does not record it)"
      ),
      (
        "config not an object",
        Some(manifestWith(ujson.Arr("policyOverriddenFrom"))),
        ujson.Null,
        ujson.Bool(false),
        "not available (the run manifest does not record it)"
      ),
      ("no manifest", None, ujson.Null, ujson.Null, "not available (run manifest not given)")
    )
    for ((name, man, value, recorded, shown) <- cases) {
      val met =
        eval(abstainRows, EvalInputs(policy = Some(policy(Some(oneWeight))), manifest = man))
      val rc = met("runContext")
      assertEquals(rc("policyOverriddenFrom"), value, name)
      assertEquals(rc("policyOverriddenFromRecorded"), recorded, name)
      val line = lineStarting(md(met), "- Policy:")
      assert(line.endsWith(s"; overridden from: $shown."), s"$name: $line")
      if (shown != "none") assert(!line.contains("overridden from: none"), s"$name: $line")
    }
  }

  // ---------------------------------------------------------------------------------------------
  // repair-17: thresholds

  private val sweepRows = List(
    label(1, "MATCH") -> keyRow(1, "accepted", p = Some(0.95)),
    label(2, "NON_MATCH") -> keyRow(2, "unresolved", p = Some(0.6)),
    label(3, "MATCH") -> keyRow(3, "rejected", p = Some(0.2))
  )

  /** The thresholds text up to the first ';' holds exactly these pairs, in any key order. */
  private def assertThresholdPairs(rest: String, pairs: String*): Unit = {
    val shown = rest.takeWhile(_ != ';').split(", ").toSet
    assertEquals(shown, pairs.toSet, rest)
  }

  private def runRows(text: String) = text.linesIterator.filter(_.contains("(run)")).toList

  test("repair-17: manifest without thresholds; policy thresholds never stand in for the run's") {
    val met = eval(
      sweepRows,
      EvalInputs(
        policy = Some(policy(Some(oneWeight))),
        manifest = Some(manifest(thresholds = None))
      )
    )
    val rc = met("runContext")
    assertEquals(rc("thresholds"), ujson.Null)
    assertEquals(rc("policyThresholds"), policyThresholds)
    val text = md(met)
    val line = lineStarting(text, "- Thresholds:")
    assert(
      line.startsWith(
        "- Thresholds: run thresholds not available (run manifest not given or without thresholds); policy file thresholds "
      ),
      line
    )
    assertThresholdPairs(line.split("policy file thresholds ")(1), "accept 0.9", "reject 0.3")
    assertEquals(runRows(text), Nil, "a sweep row is marked as the run without run thresholds")
  }

  test("repair-17: manifest thresholds that differ from the policy mark exactly the run's row") {
    val met = eval(
      sweepRows,
      EvalInputs(
        policy = Some(policy(Some(oneWeight))),
        manifest = Some(
          manifest(thresholds =
            Some(ujson.Obj("accept" -> 0.8, "reject" -> 0.2, "rationale" -> "SYNTHETIC"))
          )
        )
      )
    )
    val rc = met("runContext")
    assertEquals(rc("thresholds")("accept").num, 0.8)
    assertEquals(rc("policyThresholds"), policyThresholds)
    val text = md(met)
    assertThresholdPairs(
      lineStarting(text, "- Thresholds: ").stripPrefix("- Thresholds: "),
      "accept 0.8",
      "reject 0.2"
    )
    assert(!lineStarting(text, "- Thresholds:").contains("policy file"), "policy shown as run")
    assert(!lineStarting(text, "- Thresholds:").contains("SYNTHETIC"), "rationale rendered")
    assertEquals(runRows(text).size, 1, text)
    assert(runRows(text).head.startsWith("| 0.8 (run) | 0.2 |"), runRows(text).head)
  }

  test("repair-17: neither manifest nor policy thresholds gives 'not available' and no run row") {
    val met = eval(sweepRows, EvalInputs(manifest = Some(manifest(thresholds = None))))
    val text = md(met)
    assert(lineStarting(text, "- Thresholds:").startsWith("- Thresholds: not available;"), text)
    assertEquals(runRows(text), Nil)
  }

  test("repair-17: run thresholds off the sweep grid are kept exact; no grid row is marked") {
    // OBSERVATION pinned: the run-context line states the exact run thresholds, no sweep row is
    // marked "(run)", and the sweep section does not say that the run's thresholds are off-grid
    val met = eval(
      sweepRows,
      EvalInputs(manifest =
        Some(manifest(thresholds = Some(ujson.Obj("accept" -> 0.873, "reject" -> 0.3))))
      )
    )
    assertEquals(met("runContext")("thresholds")("accept").num, 0.873)
    val text = md(met)
    assertThresholdPairs(
      lineStarting(text, "- Thresholds: ").stripPrefix("- Thresholds: "),
      "accept 0.873",
      "reject 0.3"
    )
    assertEquals(runRows(text), Nil, "an off-grid run threshold was snapped to a grid row")
    // the grid itself is unchanged: 0.873 is never inserted as a row
    assert(
      !met("researcherResolution")("thresholdSensitivity")("grid").arr
        .exists(_("accept").num == 0.873)
    )
  }

  // ---------------------------------------------------------------------------------------------
  // inputsGiven

  test("runContext.inputsGiven records exactly which optional inputs were given") {
    val all = List("policy", "manifest", "runReport", "upstreamReport")
    for (mask <- 0 until 16) {
      val on = all.zipWithIndex.filter { case (_, i) => (mask & (1 << i)) != 0 }.map(_._1).toSet
      val inputs = EvalInputs(
        policy = Option.when(on("policy"))(policy(Some(oneWeight))),
        manifest = Option.when(on("manifest"))(manifest()),
        runReport = Option.when(on("runReport"))(runReport(None)),
        upstreamReport = Option.when(on("upstreamReport"))(ujson.Obj())
      )
      val g = eval(abstainRows, inputs)("runContext")("inputsGiven")
      all.foreach(k => assertEquals(g(k), ujson.Bool(on(k)), s"mask $mask, $k"))
    }
  }

  // ---------------------------------------------------------------------------------------------
  // EvalMain: repair-18 and the known residuals

  private final class Fixture {
    val dir: Path = Files.createTempDirectory("er-eval-repair-round-")
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
          "stratum" -> stratum("probabilistic", outcome),
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
    def required: List[String] = List(
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
    def nothingWritten(): Boolean =
      !Files.exists(out.resolve("metrics.json")) && !Files.exists(out.resolve("summary.md"))
  }

  private val roles = List("work-summary", "policy", "manifest", "run-report", "upstream-report")

  test("repair-18: malformed JSON for each optional role names that role and path; no output") {
    val malformed = List(
      "truncated" -> "{\"policyVersion\": \"SYNTHETIC\",",
      "empty" -> "",
      "trailing garbage" -> "{} SYNTHETIC",
      "bare word" -> "SYNTHETIC"
    )
    for (role <- roles; (kind, body) <- malformed) {
      val f = new Fixture
      val bad = f.write(s"$role-$kind.json", body)
      val e = intercept[IllegalArgumentException](
        EvalMain.main((f.required ++ List(s"--$role", bad.toString)).toArray)
      )
      assert(
        e.getMessage.startsWith(s"--$role $bad: not valid JSON ("),
        s"$role/$kind: ${e.getMessage}"
      )
      assert(f.nothingWritten(), s"$role/$kind: output written")
      assert(!Files.exists(f.out), s"$role/$kind: output directory created")
    }
  }

  test("repair-18: one malformed role among valid ones is the role that is named") {
    for (badRole <- roles) {
      val f = new Fixture
      val good = f.write("good.json", "{}")
      val bad = f.write("bad.json", "[")
      val args =
        roles.flatMap(r => List(s"--$r", if (r == badRole) bad.toString else good.toString))
      val e = intercept[IllegalArgumentException](EvalMain.main((f.required ++ args).toArray))
      assert(
        e.getMessage.startsWith(s"--$badRole $bad: not valid JSON"),
        s"$badRole: ${e.getMessage}"
      )
      assert(f.nothingWritten(), badRole)
    }
  }

  test("RESIDUAL pinned: a missing optional file stops the run naming the path (not the role)") {
    for (role <- roles) {
      val f = new Fixture
      val missing = f.dir.resolve(s"SYNTHETIC-missing-$role.json")
      val e = intercept[NoSuchFileException](
        EvalMain.main((f.required ++ List(s"--$role", missing.toString)).toArray)
      )
      assert(String.valueOf(e.getMessage).contains(missing.toString), s"$role: ${e.getMessage}")
      assert(f.nothingWritten(), role)
    }
  }

  test("RESIDUAL pinned: policy 'rules' that is not an array stops the run; nothing written") {
    val f = new Fixture
    val pol =
      f.write("policy.json", policy(Some(oneWeight)).render().dropRight(1) + ",\"rules\":{}}")
    intercept[Exception](EvalMain.main((f.required ++ List("--policy", pol.toString)).toArray))
    assert(f.nothingWritten())
  }

  /** A run either writes both outputs or, when it fails, neither: never a metrics.json that looks
    * complete next to a missing summary.md.
    */
  private def assertAllOrNothing(f: Fixture, run: scala.util.Try[Unit], what: String): Unit = {
    val metrics = Files.exists(f.out.resolve("metrics.json"))
    val summary = Files.exists(f.out.resolve("summary.md"))
    if (run.isFailure)
      assert(
        !metrics && !summary,
        s"$what: the run failed (${run.failed.get.getClass.getSimpleName}: ${run.failed.get.getMessage}) but left metrics.json=$metrics, summary.md=$summary"
      )
    else assert(metrics && summary, s"$what: run succeeded with metrics=$metrics summary=$summary")
  }

  test("FINDING EvalMain: a non-object recall-gap element leaves no partial output") {
    val f = new Fixture
    val rr = f.write("run-report.json", "{\"knownRecallGaps\": [\"SYNTHETIC gap as a string\"]}")
    val run =
      scala.util.Try(EvalMain.main((f.required ++ List("--run-report", rr.toString)).toArray))
    assertAllOrNothing(f, run, "knownRecallGaps element is a string")
  }

  test("FINDING EvalMain: non-numeric manifest thresholds leave no partial output") {
    val f = new Fixture
    val man = f.write(
      "manifest.json",
      manifest(thresholds = Some(ujson.Obj("accept" -> "0.9", "reject" -> "0.3"))).render()
    )
    val run =
      scala.util.Try(EvalMain.main((f.required ++ List("--manifest", man.toString)).toArray))
    assertAllOrNothing(f, run, "manifest thresholds are strings")
  }
}
