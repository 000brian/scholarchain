package org.scholarchain.contracts.er

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.MessageDigest

import org.scholarchain.er.eval.{EvalInputs, EvalMain, GoldLabel, GoldSet, Harness, KeyRow}

/** Adversarial tests of the Phase 5 run-2 evaluation-report repairs (08 to 11): error-row
  * traceability, error selection and truncation, gold-set counts, the LLM-path statement, optional
  * inputs that are missing, malformed or incomplete, provenance hashes, upstream run-ID checks, m4
  * with several memberships, and non-mutation of the inputs by the threshold sweep.
  *
  * Every label, queue row, key row and run artifact is clearly synthetic and built in the test;
  * nothing reads docs/agent-runs or the human gold file.
  */
class EvalReportAdversarialSuite extends munit.FunSuite {

  // ---------------------------------------------------------------------------------------------
  // Synthetic builders

  private def m(i: Int) = f"mention:v1:$i%032x"
  private def c(i: Int) = f"match-candidate:v1:$i%032x"
  private def pair(i: Int) = (m(2 * i - 1), m(2 * i))

  private def stratum(path: String, outcome: String, family: String = "SYNTH") =
    s"path=$path;outcome=$outcome;family=$family;band=far"

  /** One labeled row `i`: the human label and the hidden key row. */
  private def label(i: Int, lab: String, note: String = "SYNTHETIC") = GoldLabel(
    m(2 * i - 1),
    m(2 * i),
    c(i),
    lab,
    if (lab == "UNSURE") Nil else List("orcid"),
    note,
    "SYNTHETIC-LABELER",
    "2026-11-14"
  )

  private def keyRow(
      i: Int,
      outcome: String,
      path: String = "probabilistic",
      p: Option[Double] = Some(0.5),
      memberships: Option[List[(String, Double, String, String)]] = None,
      reasons: List[String] = Nil
  ): KeyRow = {
    val ms = memberships.getOrElse(List((stratum(path, outcome), 50.0, outcome, path)))
    KeyRow(
      m(2 * i - 1),
      m(2 * i),
      c(i),
      ms.map(x => (x._1, x._2)),
      outcome,
      path,
      "SYNTH",
      p,
      0.5,
      List("name_exact_match" -> 1.0),
      Nil,
      reasons,
      ms.map(x => (x._3, x._4)),
      Some(f"researcher:v1:${2 * i - 1}%032x"),
      Some(f"researcher:v1:${2 * i}%032x"),
      Some(false)
    )
  }

  private def queueRow(i: Int): Map[String, String] = Map(
    "rowNumber" -> i.toString,
    "leftMentionId" -> m(2 * i - 1),
    "rightMentionId" -> m(2 * i),
    "candidateId" -> c(i),
    "left_name" -> s"SYNTHETIC Left $i",
    "left_dblpKey" -> s"SYNTHETIC/key/$i/L",
    "left_dblpUrl" -> s"https://dblp.org/rec/SYNTHETIC/key/$i/L.html",
    "right_name" -> s"SYNTHETIC Right $i",
    "right_dblpKey" -> s"SYNTHETIC/key/$i/R",
    "right_dblpUrl" -> s"https://dblp.org/rec/SYNTHETIC/key/$i/R.html"
  )

  private def queueOf(is: Seq[Int]) = is.map(i => pair(i) -> queueRow(i)).toMap

  /** Rows given as (i, label, key row); the gold labels are passed in the given order. */
  private def eval(
      rows: List[(GoldLabel, KeyRow)],
      inputs: EvalInputs = EvalInputs(),
      unlabeled: Int = 0
  ): ujson.Obj =
    Harness.evaluate(
      GoldSet(rows.map(_._1), unlabeled),
      rows.map(r => (r._2.left, r._2.right) -> r._2).toMap,
      None,
      7L,
      20,
      Some("SYNTHETIC"),
      inputs
    )

  private def manifest(
      adjudicator: String = "always-abstain",
      model: String = "none/always-abstain",
      inputRunHex: String = "a" * 32,
      thresholds: ujson.Value = ujson.Obj("accept" -> 0.9, "reject" -> 0.3)
  ) = ujson.Obj(
    "runId" -> "run:v1:SYNTHETIC",
    "inputRun" -> ujson.Obj(
      "jobName" -> "SYNTHETIC.upstream",
      "runId" -> s"run:v1:$inputRunHex",
      "snapshotId" -> "SYNTHETIC-snapshot"
    ),
    "config" -> ujson.Obj(
      "policySha256" -> "SYNTHETIC-sha",
      "policyOverriddenFrom" -> ujson.Null,
      "thresholds" -> thresholds,
      "llmModelId" -> model,
      "adjudicator" -> adjudicator
    )
  )

  private def upstream(exportHex: String) = ujson.Obj(
    "settings" -> ujson.Obj(
      "scholarchain.blocking.fallback.maxBlockSize" -> "200",
      "scholarchain.blocking.maxBlockSize" -> "200"
    ),
    "summary" -> ujson.Obj(
      "exportDir" -> s"file:/SYNTHETIC/j09-graph-export/run=$exportHex",
      "fallback" -> ujson.Obj(
        "uncoveredMembers" -> 11,
        "uncoveredPerFamily" -> ujson.Obj("SYNTH" -> 11)
      )
    )
  )

  private val syntheticPolicy = ujson.Obj(
    "policyVersion" -> "SYNTHETIC-policy",
    "status" -> "SYNTHETIC",
    "priorLogOdds" -> -1.0,
    "thresholds" -> ujson.Obj("accept" -> 0.9, "reject" -> 0.3),
    "weights" -> ujson.Arr(
      ujson.Obj(
        "feature" -> "name_exact_match",
        "bins" -> ujson.Arr(ujson.Obj("min" -> 0, "max" -> ujson.Null, "weight" -> 1.5)),
        "scaledBySourceReliability" -> false,
        "rationale" -> "SYNTHETIC"
      )
    ),
    "rules" -> ujson.Arr(
      ujson.Obj("id" -> "SYNTH.rule", "kind" -> "accept", "path" -> "deterministic")
    )
  )

  private def ids(list: ujson.Value): List[(String, String)] =
    list.arr.toList.map(x => (x("leftMentionId").str, x("rightMentionId").str))

  // ---------------------------------------------------------------------------------------------
  // repair-08: traceability

  test("repair-08: an error row without a queue row is shown as not available, never defaulted") {
    // row 1 has its queue row; row 2 (also a false positive) has none; row 3 is an unrelated row
    val rows = List(
      label(2, "NON_MATCH") -> keyRow(2, "accepted"),
      label(1, "NON_MATCH") -> keyRow(1, "accepted")
    )
    val met = eval(rows, EvalInputs(queue = queueOf(Seq(1, 3))))
    val fps = met("researcherResolution")("errorAnalysis")("falsePositives").arr
    assertEquals(fps.size, 2)
    assertEquals(ids(ujson.Arr.from(fps)), List(pair(1), pair(2)))
    // row 1 carries exactly its own queue values (not those of row 3)
    assertEquals(fps(0)("rowNumber"), ujson.Str("1"))
    assertEquals(fps(0)("leftDblpKey"), ujson.Str("SYNTHETIC/key/1/L"))
    assertEquals(fps(0)("rightDblpUrl"), ujson.Str("https://dblp.org/rec/SYNTHETIC/key/1/R.html"))
    // row 2: every trace field is null, none borrowed or blank-filled
    List(
      "rowNumber",
      "leftName",
      "leftDblpKey",
      "leftDblpUrl",
      "rightName",
      "rightDblpKey",
      "rightDblpUrl"
    ).foreach(f => assertEquals(fps(1)(f), ujson.Null, s"field $f of the row without queue row"))
    val md = Harness.markdown(met)
    assert(
      md.contains(
        "1. Queue row 1: left SYNTHETIC Left 1 (dblp key SYNTHETIC/key/1/L, https://dblp.org/rec/SYNTHETIC/key/1/L.html)"
      ),
      md
    )
    assert(
      md.contains(
        "2. Queue row not available: left not available (dblp key not available, not available); right not available"
      ),
      md
    )
    assert(!md.contains("SYNTHETIC/key/3/"), "row 3 is no error row and must not appear")
  }

  test("repair-08: with no queue input every error row is not available, never another row") {
    val met = eval(List(label(1, "MATCH") -> keyRow(1, "rejected")))
    val fn = met("researcherResolution")("errorAnalysis")("falseNegatives").arr.head
    assertEquals(fn("rowNumber"), ujson.Null)
    assertEquals(fn("leftDblpKey"), ujson.Null)
    assertEquals(fn("leftMentionId").str, m(1))
    assert(Harness.markdown(met).contains("1. Queue row not available: left not available"))
  }

  test("repair-08: FP/FN selection is the first 10 in mention-ID order, totals are full counts") {
    val fpRows = (1 to 13).map(i => label(i, "NON_MATCH") -> keyRow(i, "accepted"))
    val fnOutcomes = List("rejected", "unresolved", "conflict")
    val fnRows =
      (14 to 24).map(i => label(i, "MATCH") -> keyRow(i, fnOutcomes(i % 3)))
    val others = List(
      label(25, "UNSURE") -> keyRow(25, "accepted"),
      label(26, "UNSURE") -> keyRow(26, "unresolved"),
      label(27, "NON_MATCH") -> keyRow(27, "rejected"),
      label(28, "MATCH") -> keyRow(28, "accepted")
    )
    // scrambled input order: reversed, interleaved
    val all = (fpRows ++ fnRows ++ others).toList.reverse
    val scrambled = all.zipWithIndex.sortBy { case (_, k) => (k % 3, -k) }.map(_._1)
    val met = eval(scrambled, EvalInputs(queue = queueOf(1 to 28)))
    val e = met("researcherResolution")("errorAnalysis")
    assertEquals(e("falsePositivesTotal").num, 13.0)
    assertEquals(e("falseNegativesTotal").num, 11.0)
    assertEquals(ids(e("falsePositives")), (1 to 10).map(pair).toList)
    assertEquals(ids(e("falseNegatives")), (14 to 23).map(pair).toList)
    // conflict and unresolved MATCH rows are false negatives too
    assert(e("falseNegatives").arr.exists(_("outcome").str == "conflict"))
    assert(e("falseNegatives").arr.exists(_("outcome").str == "unresolved"))
    // UNSURE, true negatives and true positives never appear
    val listed = (ids(e("falsePositives")) ++ ids(e("falseNegatives"))).toSet
    List(25, 26, 27, 28).foreach(i => assert(!listed(pair(i)), s"row $i listed as an error"))
    // each listed row keeps its own queue row number
    e("falsePositives").arr.zip(1 to 10).foreach { case (x, i) =>
      assertEquals(x("rowNumber").str, i.toString)
    }
    val md = Harness.markdown(met)
    assert(
      md.contains("#### False positives (accepted, labeled NON_MATCH): 13 total, 10 shown"),
      md
    )
    assert(
      md.contains("#### False negatives (labeled MATCH, not accepted): 11 total, 10 shown"),
      md
    )
    // the 11th to 13th false positives and the 11th false negative are not shown
    List(11, 12, 13, 24, 25, 26).foreach(i =>
      assert(!md.contains(m(2 * i - 1)), s"row $i must not be shown in the Markdown")
    )
  }

  // ---------------------------------------------------------------------------------------------
  // repair-09: gold set and UNSURE isolation

  test("repair-09: gold-set counts; UNSURE and unlabeled rows never enter precision or recall") {
    val s = stratum("probabilistic", "accepted")
    def k(i: Int, o: String) = keyRow(i, o, memberships = Some(List((s, 60.0, o, "probabilistic"))))
    val base = List(
      label(1, "MATCH") -> k(1, "accepted"),
      label(2, "NON_MATCH") -> k(2, "accepted"),
      label(3, "MATCH") -> k(3, "unresolved"),
      label(4, "NON_MATCH") -> k(4, "rejected")
    )
    val withUnsure =
      base ++ List(label(5, "UNSURE") -> k(5, "accepted"), label(6, "UNSURE") -> k(6, "rejected"))
    val q = queueOf(1 to 8)
    val a = eval(base, EvalInputs(queue = q))
    val b = eval(withUnsure, EvalInputs(queue = q), unlabeled = 1)

    val g = b("goldSet")
    assertEquals(g("queueRows").num, 8.0)
    assertEquals(g("rowsInGoldFile").num, 7.0)
    assertEquals(g("queueRowsAbsentFromGoldFile").num, 1.0)
    assertEquals(g("labeledRows").num, 6.0)
    assertEquals(g("byLabel")("MATCH").num, 2.0)
    assertEquals(g("byLabel")("NON_MATCH").num, 2.0)
    assertEquals(g("byLabel")("UNSURE").num, 2.0)
    assertEquals(g("unsureExcludedFromPrecisionAndRecall").num, 2.0)
    assertEquals(g("unlabeledRows").num, 1.0)

    for (set <- List("unweighted")) {
      val (x, y) = (a("researcherResolution")(set), b("researcherResolution")(set))
      for (metric <- List("precision", "recall", "coverage")) {
        assertEquals(y(metric)("value"), x(metric)("value"), s"$set $metric changed by UNSURE")
        assertEquals(y(metric)("denominator"), x(metric)("denominator"), s"$set $metric den")
      }
      assertEquals(x("precision")("value").num, 0.5)
      assertEquals(x("recall")("value").num, 0.5)
      // UNSURE stays in the unresolved-rate denominator only
      assertEquals(y("unresolvedRate")("denominator").num, 6.0)
      assertEquals(y("confusion")("accepted")("UNSURE").num, 1.0)
    }
    // one stratum: the weighted ratios are unchanged by UNSURE rows as well
    val (wx, wy) = (
      a("researcherResolution")("weighted")("metrics"),
      b("researcherResolution")("weighted")("metrics")
    )
    for (metric <- List("precision", "recall", "coverage"))
      assertEqualsDouble(wy(metric)("value").num, wx(metric)("value").num, 1e-12, metric)

    val md = Harness.markdown(b)
    assert(
      md.contains(
        "- Review-queue rows: 8; rows in the gold file: 7; queue rows absent from the gold file: 1."
      ),
      md
    )
    assert(
      md.contains("- Labeled rows: 6 (MATCH 2, NON_MATCH 2, UNSURE 2); unlabeled rows: 1."),
      md
    )
    // without a queue input the queue counts are not available, not zero
    val noQ = eval(base)
    assertEquals(noQ("goldSet")("queueRows"), ujson.Null)
    assertEquals(noQ("goldSet")("queueRowsAbsentFromGoldFile"), ujson.Null)
    assert(Harness.markdown(noQ).contains("- Review-queue rows: not available;"))
  }

  // ---------------------------------------------------------------------------------------------
  // repair-09: LLM path

  private val llmRows = List(
    label(1, "MATCH") -> keyRow(1, "unresolved", path = "llm", reasons = List("llm-abstain")),
    label(2, "NON_MATCH") -> keyRow(2, "unresolved", path = "llm", reasons = List("llm-abstain")),
    label(3, "MATCH") -> keyRow(3, "accepted", path = "deterministic", p = None)
  )

  test("repair-09: LLM statement with the always-abstain adjudicator and abstaining rows") {
    val met = eval(llmRows, EvalInputs(manifest = Some(manifest())))
    val l = met("researcherResolution")("llmPath")
    assertEquals(l("labeledRows").num, 2.0)
    assertEquals(l("adjudicatedAcceptOrReject").num, 0.0)
    assertEquals(l("outcomes")("unresolved").num, 2.0)
    assertEquals(l("reasonCodes")("llm-abstain").num, 2.0)
    assertEquals(l("adjudicator").str, "always-abstain")
    assert(l("statement").str.startsWith("LLM-adjudicated metrics are empty"), l("statement").str)
    assert(Harness.markdown(met).contains("### LLM-adjudicated path"))
  }

  test("repair-09: LLM rows that the adjudicator decided are counted, not called empty") {
    val rows = List(
      label(1, "MATCH") -> keyRow(1, "accepted", path = "llm"),
      label(2, "NON_MATCH") -> keyRow(2, "rejected", path = "llm"),
      label(3, "MATCH") -> keyRow(3, "unresolved", path = "llm", reasons = List("llm-abstain"))
    )
    val met = eval(rows, EvalInputs(manifest = Some(manifest("SYNTHETIC-live", "SYNTHETIC/m"))))
    val l = met("researcherResolution")("llmPath")
    assertEquals(l("adjudicatedAcceptOrReject").num, 2.0)
    assertEquals(l("labeledRows").num, 3.0)
    assert(l("statement").str.startsWith("2 of 3 labeled llm-path rows"), l("statement").str)
    assert(!l("statement").str.contains("empty"), l("statement").str)
    assertEquals(met("researcherResolution")("byMatchPath")("llm")("units").num, 3.0)
  }

  test(
    "FINDING repair-09: LLM statement must not claim a non-abstaining adjudicator never decides"
  ) {
    // zero labeled llm rows; the manifest names a live adjudicator and the run report shows it
    // accepted and rejected pairs
    val runReport = ujson.Obj(
      "llm" -> ujson.Obj(
        "outcomes" -> ujson.Obj("accepted" -> 3, "rejected" -> 2),
        "pairsSentToAdjudicator" -> 5,
        "bandCandidates" -> 5
      )
    )
    val met = eval(
      List(llmRows(2)),
      EvalInputs(
        manifest = Some(manifest("SYNTHETIC-live-adjudicator", "SYNTHETIC/model-1")),
        runReport = Some(runReport)
      )
    )
    val st = met("researcherResolution")("llmPath")("statement").str
    assert(
      !st.contains("never accepts or rejects"),
      s"statement attributes 'never accepts or rejects' to adjudicator SYNTHETIC-live-adjudicator, whose run report shows 3 accepted and 2 rejected: $st"
    )
    assert(
      !st.contains("every LLM band pair stays unresolved"),
      s"statement contradicts the run report (3 accepted, 2 rejected): $st"
    )
  }

  test("FINDING repair-09: LLM statement must not assert behaviour of an adjudicator not given") {
    val met = eval(llmRows) // no manifest: the adjudicator is unknown
    val st = met("researcherResolution")("llmPath")("statement").str
    assertEquals(met("researcherResolution")("llmPath")("adjudicator"), ujson.Null)
    assert(
      !st.contains("which never accepts or rejects"),
      s"statement asserts that an adjudicator that is not available never accepts or rejects: $st"
    )
  }

  // ---------------------------------------------------------------------------------------------
  // repair-10 and 11: optional inputs present but incomplete

  test("FINDING repair-10: a policy file without 'weights' is not available, never empty") {
    val policy = ujson.Obj("policyVersion" -> "SYNTHETIC-policy-without-weights")
    val met = eval(List(llmRows(2)), EvalInputs(policy = Some(policy)))
    val rc = met("runContext")
    assertEquals(
      rc("weightedFeatures"),
      ujson.Null,
      "a missing 'weights' field was defaulted to an empty list"
    )
    assertEquals(
      rc("recordedFeaturesWithoutWeight"),
      ujson.Null,
      "with no weights field every recorded feature is reported as unweighted, a fabricated claim"
    )
  }

  test("FINDING repair-11: a run report without 'knownRecallGaps' is not available, never empty") {
    val runReport = ujson.Obj("inputs" -> ujson.Obj("works" -> 10))
    val met = eval(List(llmRows(2)), EvalInputs(runReport = Some(runReport)))
    assertEquals(
      met("knownLimitations")("knownRecallGaps"),
      ujson.Null,
      "a missing 'knownRecallGaps' field was defaulted to an empty list (no gaps)"
    )
  }

  test("FINDING repair-10: without a manifest the policy override is not available, not 'none'") {
    val met = eval(List(llmRows(2)), EvalInputs(policy = Some(syntheticPolicy)))
    assertEquals(met("runContext")("policyOverriddenFrom"), ujson.Null)
    val md = Harness.markdown(met)
    val line = md.linesIterator.find(_.startsWith("- Policy:")).getOrElse(fail(md))
    assert(
      !line.contains("overridden from: none"),
      s"manifest not given, yet the Markdown states the policy was not overridden: $line"
    )
  }

  test("repair-10: missing inputs are shown as not available in run context and limitations") {
    val met = eval(List(llmRows(2)))
    val rc = met("runContext")
    List("policyVersion", "thresholds", "runId", "adjudicator", "weightedFeatures", "rules")
      .foreach(f => assertEquals(rc(f), ujson.Null, f))
    val k = met("knownLimitations")
    assertEquals(k("knownRecallGaps"), ujson.Null)
    assertEquals(k("blockingCoverage")("uncoveredMembers"), ujson.Null)
    assertEquals(k("blockingCoverage")("upstreamRunMatchesErInputRun"), ujson.Null)
    val md = Harness.markdown(met)
    assert(md.contains("Policy file not given: feature definitions not available."), md)
    assert(md.contains("- Known recall gaps: run report not given."), md)
    assert(md.contains("Upstream run is the ER input run: not available."), md)
    assert(!md.contains(" (run) |"), "no thresholds given, yet a sweep row is marked as the run")
  }

  // ---------------------------------------------------------------------------------------------
  // repair-11: upstream run-ID and m3/m4

  test("repair-11: an upstream report of another run is reported as a mismatch, not hidden") {
    val hexA = "a" * 32
    val hexB = "b" * 31 + "a"
    def bc(up: Option[ujson.Value], man: Option[ujson.Value]) =
      eval(List(llmRows(2)), EvalInputs(manifest = man, upstreamReport = up))
    val same = bc(Some(upstream(hexA)), Some(manifest(inputRunHex = hexA)))
    assertEquals(
      same("knownLimitations")("blockingCoverage")("upstreamRunMatchesErInputRun"),
      ujson.Bool(true)
    )
    val other = bc(Some(upstream(hexB)), Some(manifest(inputRunHex = hexA)))
    assertEquals(
      other("knownLimitations")("blockingCoverage")("upstreamRunMatchesErInputRun"),
      ujson.Bool(false)
    )
    // a run ID that is only a suffix of the export run directory name does not match
    val suffix = bc(Some(upstream("0" + "a" * 31)), Some(manifest(inputRunHex = "a" * 31)))
    assertEquals(
      suffix("knownLimitations")("blockingCoverage")("upstreamRunMatchesErInputRun"),
      ujson.Bool(false)
    )
    val md = Harness.markdown(other)
    assert(md.contains("Upstream run is the ER input run: false."), md)
    // upstream given without a manifest: the check cannot be made and is not available
    val noMan = bc(Some(upstream(hexA)), None)
    assertEquals(
      noMan("knownLimitations")("blockingCoverage")("upstreamRunMatchesErInputRun"),
      ujson.Null
    )
    assert(Harness.markdown(noMan).contains("Upstream run is the ER input run: not available."))
  }

  test("repair-11: m3 and m4 count memberships of a row with several memberships") {
    val prior = stratum("deterministic", "accepted", "dblp-prior")
    val cand = stratum("probabilistic", "unresolved")
    val rows = List(
      label(1, "MATCH") -> keyRow(
        1,
        "unresolved",
        memberships = Some(
          List(
            (prior, 200.0, "accepted", "deterministic"),
            (cand, 40.0, "unresolved", "probabilistic")
          )
        )
      ),
      label(2, "NON_MATCH") -> keyRow(
        2,
        "conflict",
        path = "exact_id",
        memberships = Some(List((cand, 40.0, "conflict", "exact_id")))
      ),
      label(3, "MATCH") -> keyRow(
        3,
        "unresolved",
        memberships = Some(List((cand, 40.0, "unresolved", "probabilistic")))
      )
    )
    val met = eval(rows)
    val k = met("knownLimitations")
    val d = k("decisionsNotPairs")
    assertEquals(d("labeledRows").num, 3.0)
    assertEquals(d("labeledMemberships").num, 4.0)
    assertEquals(d("rowsWithSeveralMemberships").num, 1.0)
    val st = k("stratumLabelVsDecision")("strata")
    assertEquals(st.obj.keySet.toSet, Set(cand), "only the candidate stratum has a differing row")
    assertEquals(st(cand)("labeledMemberships").num, 3.0)
    assertEquals(st(cand)("membershipsWhoseDecisionDiffers").num, 1.0)
    assertEquals(st(cand)("decisions").obj.toMap, Map("exact_id/conflict" -> ujson.Num(1)))
    // weighted metrics count the 4 memberships, unweighted the 3 rows
    assertEquals(met("researcherResolution")("weighted")("metrics")("units").num, 4.0)
    assertEquals(met("researcherResolution")("unweighted")("units").num, 3.0)
    val md = Harness.markdown(met)
    assert(
      md.contains(
        "In this gold set: 3 labeled rows, 4 memberships, 1 rows with several memberships."
      ),
      md
    )
    assert(
      md.contains(s"  - $cand: 1 of 3 labeled memberships have decision exact_id/conflict 1."),
      md
    )
  }

  // ---------------------------------------------------------------------------------------------
  // threshold sweep never mutates its inputs

  test("threshold sweep: inputs are not mutated and exactly the run's thresholds are marked") {
    val rows = List(
      label(1, "MATCH") -> keyRow(1, "accepted", p = Some(0.95)),
      label(2, "NON_MATCH") -> keyRow(2, "unresolved", p = Some(0.6)),
      label(3, "MATCH") -> keyRow(3, "rejected", p = Some(0.2))
    )
    val pol = ujson.read(syntheticPolicy.render())
    val man = ujson.read(manifest().render())
    val before = (pol.render(), man.render())
    val withInputs = eval(rows, EvalInputs(policy = Some(pol), manifest = Some(man)))
    val md = Harness.markdown(withInputs)
    withInputs.render()
    assertEquals((pol.render(), man.render()), before, "evaluate or markdown mutated an input")
    val runRows = md.linesIterator.filter(_.contains(" (run) |")).toList
    assertEquals(runRows, runRows.filter(_.startsWith("| 0.9 (run) | 0.3 |")))
    assertEquals(runRows.size, 1, md)
    // the sweep grid does not depend on the inputs: identical with and without policy/manifest
    val bare = eval(rows)
    assertEquals(
      withInputs("researcherResolution")("thresholdSensitivity")("grid"),
      bare("researcherResolution")("thresholdSensitivity")("grid")
    )
    // and the run's own decisions are unchanged by the sweep
    assertEquals(
      withInputs("researcherResolution")("unweighted")("confusion"),
      bare("researcherResolution")("unweighted")("confusion")
    )
  }

  // ---------------------------------------------------------------------------------------------
  // EvalMain: provenance, missing and malformed inputs

  private def sha256Hex(b: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(b).map(x => f"${x & 0xff}%02x").mkString

  private final class Fixture {
    val dir: Path = Files.createTempDirectory("er-eval-adversarial-")
    def write(name: String, text: String): Path = {
      val p = dir.resolve(name)
      Files.write(p, text.getBytes(StandardCharsets.UTF_8))
      p
    }
    private val rowsI = List(1, 2, 3)
    val gold: Path = write(
      "gold.csv",
      ("leftMentionId,rightMentionId,candidateId,label,evidence,note,labeler,labeledAt" ::
        List(
          s"${m(1)},${m(2)},${c(1)},MATCH,orcid,SYNTHETIC,SYNTHETIC-LABELER,2026-11-14",
          s"${m(3)},${m(4)},${c(2)},NON_MATCH,orcid,SYNTHETIC,SYNTHETIC-LABELER,2026-11-14",
          s"${m(5)},${m(6)},${c(3)},UNSURE,,SYNTHETIC,SYNTHETIC-LABELER,2026-11-14"
        )).mkString("", "\r\n", "\r\n")
    )
    val queue: Path = write(
      "queue.jsonl",
      rowsI
        .map(i => ujson.write(ujson.Obj.from(queueRow(i).map { case (k, v) => k -> ujson.Str(v) })))
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
      List(keyJson(1, "rejected"), keyJson(2, "accepted"), keyJson(3, "accepted"))
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
  }

  test("repair-10: provenance records every input's role, path and sha256 of its exact bytes") {
    val f = new Fixture
    // CRLF, indentation and a trailing blank line: the hash must be of the raw bytes
    val work = f.write("work.json", "{\r\n  \"subsetWorks\": 3\r\n}\r\n\r\n")
    val pol = f.write("policy.json", syntheticPolicy.render(indent = 4))
    val man = f.write("manifest.json", manifest().render(indent = 1) + "\n")
    val rr = f.write("run-report.json", "{\"knownRecallGaps\": []}")
    val up = f.write("upstream.json", upstream("a" * 32).render())
    val args = f.required ++ List(
      "--work-summary",
      work.toString,
      "--policy",
      pol.toString,
      "--manifest",
      man.toString,
      "--run-report",
      rr.toString,
      "--upstream-report",
      up.toString
    )
    EvalMain.main(args.toArray)
    val met = ujson.read(Files.readAllBytes(f.out.resolve("metrics.json")))
    val prov =
      met("provenance").arr.toList.map(p => (p("role").str, p("path").str, p("sha256").str))
    val expected = List(
      "gold" -> f.gold,
      "queue" -> f.queue,
      "key" -> f.key,
      "work-summary" -> work,
      "policy" -> pol,
      "manifest" -> man,
      "run-report" -> rr,
      "upstream-report" -> up
    ).map { case (r, p) => (r, p.toString, sha256Hex(Files.readAllBytes(p))) }
    assertEquals(prov, expected)
    // upstream export run matches the manifest's input run
    assertEquals(
      met("knownLimitations")("blockingCoverage")("upstreamRunMatchesErInputRun"),
      ujson.Bool(true)
    )
    // every hash appears in the Markdown inputs table
    val md = new String(Files.readAllBytes(f.out.resolve("summary.md")), StandardCharsets.UTF_8)
    expected.foreach { case (r, _, sha) =>
      assert(md.contains(s"| $r | "), r); assert(md.contains(sha))
    }
    // the gold counts came through the strict reader: UNSURE counted, out of precision
    assertEquals(met("goldSet")("byLabel")("UNSURE").num, 1.0)
    assertEquals(met("researcherResolution")("unweighted")("precision")("denominator").num, 1.0)
  }

  test("repair-10: only the inputs actually given appear in provenance") {
    val f = new Fixture
    EvalMain.main(f.required.toArray)
    val met = ujson.read(Files.readAllBytes(f.out.resolve("metrics.json")))
    assertEquals(met("provenance").arr.map(_("role").str).toList, List("gold", "queue", "key"))
    assertEquals(met("runContext")("policyVersion"), ujson.Null)
    assertEquals(met("goldSet")("queueRows").num, 3.0)
  }

  test("repair-10: a missing optional input file stops the run; nothing is written") {
    val f = new Fixture
    val missing = f.dir.resolve("no-such-policy.json")
    for (role <- List("policy", "manifest", "run-report", "upstream-report", "work-summary")) {
      intercept[Exception](
        EvalMain.main((f.required ++ List(s"--$role", missing.toString)).toArray)
      )
      assert(!Files.exists(f.out.resolve("metrics.json")), s"metrics written with missing --$role")
      assert(!Files.exists(f.out.resolve("summary.md")), s"summary written with missing --$role")
    }
  }

  test("repair-10: a malformed optional JSON input stops the run; nothing is written") {
    val f = new Fixture
    val bad = f.write("bad.json", "{\"policyVersion\": \"SYNTHETIC\",")
    for (role <- List("policy", "manifest", "run-report", "upstream-report", "work-summary")) {
      intercept[Exception](EvalMain.main((f.required ++ List(s"--$role", bad.toString)).toArray))
      assert(
        !Files.exists(f.out.resolve("metrics.json")),
        s"metrics written with malformed --$role"
      )
    }
  }

  test("repair-10: unknown and dangling arguments are rejected") {
    val f = new Fixture
    val e1 = intercept[IllegalArgumentException](
      EvalMain.main((f.required ++ List("--polcy", "x.json")).toArray)
    )
    assert(e1.getMessage.contains("polcy"), e1.getMessage)
    intercept[IllegalArgumentException](EvalMain.main((f.required :+ "--policy").toArray))
    assert(!Files.exists(f.out.resolve("metrics.json")))
  }
}
