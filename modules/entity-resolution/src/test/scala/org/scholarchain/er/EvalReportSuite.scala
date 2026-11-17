package org.scholarchain.er

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import org.scholarchain.er.eval.*
import org.scholarchain.er.io.DataFiles
import org.scholarchain.er.review.ReviewQueue

/** Phase 5 run 2 repairs 08 to 11: the evaluation report carries everything the run-2 work order
  * (decision 7) asks for. Synthetic labels and synthetic run artifacts only.
  */
class EvalReportSuite extends munit.FunSuite {

  private def resource(name: String): Path =
    Paths.get(getClass.getClassLoader.getResource(s"synthetic-gold/$name").toURI)
  private val goldText =
    new String(Files.readAllBytes(resource("gold-valid.csv")), StandardCharsets.UTF_8)
  private def hex(i: Int) = f"$i%032x"

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

  private val gold = GoldLabels.parse(goldText, queue).fold(e => fail(e.mkString("\n")), identity)
  private val key = KeyRow.readAll(resource("review-key.jsonl"))
  private val row8 = (s"mention:v1:${hex(15)}", s"mention:v1:${hex(16)}")

  private val manifest = ujson.Obj(
    "runId" -> "run:v1:SYNTHETIC",
    "codeVersion" -> "SYNTHETIC-code",
    "decisionTime" -> "2026-11-14T00:00:00Z",
    "inputRun" -> ujson.Obj(
      "jobName" -> "SYNTHETIC.upstream",
      "runId" -> s"run:v1:${hex(77)}",
      "snapshotId" -> "SYNTHETIC-snapshot"
    ),
    "config" -> ujson.Obj(
      "policySha256" -> "SYNTHETIC-sha",
      "policyOverriddenFrom" -> ujson.Null,
      "thresholds" -> ujson.Obj("accept" -> 0.9, "reject" -> 0.3),
      "promptVersion" -> "SYNTHETIC-PROMPT/1",
      "promptSha256" -> "SYNTHETIC-prompt-sha",
      "outputSchemaVersion" -> "SYNTHETIC-SCHEMA/1",
      "outputSchemaSha256" -> "SYNTHETIC-schema-sha",
      "llmModelId" -> "none/always-abstain",
      "adjudicator" -> "always-abstain",
      "nameKeyVersion" -> "SYNTHETIC-key/1",
      "candidateFeatureSet" -> "SYNTHETIC-features/1"
    )
  )
  private val runReport = ujson.Obj(
    "inputs" -> ujson.Obj("venues" -> 16, "works" -> 100, "mentionsWithoutCandidates" -> 13),
    "llm" -> ujson.Obj(
      "outcomes" -> ujson.Obj("unresolved" -> 4),
      "reasons" -> ujson.Obj("llm-abstain" -> 4),
      "pairsSentToAdjudicator" -> 4,
      "bandCandidates" -> 9
    ),
    "knownRecallGaps" -> ujson.Arr(
      ujson.Obj(
        "id" -> "blocking.SYNTHETIC-gap",
        "description" -> "SYNTHETIC gap description.",
        "firstInitialSurnameGroupsSplitByMiddleInitials" -> 5,
        "dblpNamesInThoseGroups" -> 7
      )
    )
  )
  private def upstream(runHex: String) = ujson.Obj(
    "settings" -> ujson.Obj(
      "scholarchain.blocking.fallback.maxBlockSize" -> "200",
      "scholarchain.blocking.maxBlockSize" -> "200"
    ),
    "summary" -> ujson.Obj(
      "exportDir" -> s"file:/SYNTHETIC/j09/run=$runHex",
      "fallback" -> ujson.Obj(
        "uncoveredMembers" -> 11,
        "uncoveredPerFamily" -> ujson.Obj("SYNTHETIC-family" -> 11)
      )
    )
  )
  private val workSummary = ujson.Obj(
    "subsetWorks" -> 100,
    "externalIdLinkDirectoryPresent" -> false,
    "exactDoiLinkCoverage" -> ujson.Obj("works" -> 0, "share" -> 0),
    "exactOpenAlexWorkLinkCoverage" -> ujson.Obj("works" -> 0, "share" -> 0),
    "workLinksOutsideSubset" -> 0,
    "j04ConflictsDirectoryGiven" -> false,
    "j04ConflictsByReason" -> ujson.Obj(),
    "identifierAvailability" -> ujson.Obj(
      "dblpDoi" -> ujson.Obj("works" -> 80, "share" -> 0.8),
      "semanticScholarId" -> "not applicable: SYNTHETIC"
    ),
    "dblpIdentifierCollisions" -> ujson.Obj(
      "landingUrl" -> ujson.Obj("groups" -> 1, "works" -> 2)
    ),
    "probabilisticWorkMatching" -> "not implemented: SYNTHETIC (finding 11)"
  )
  private def policy: ujson.Value = {
    val p = Paths.get(
      getClass.getClassLoader.getResource(org.scholarchain.er.policy.Policy.DefaultResource).toURI
    )
    ujson.read(DataFiles.readUtf8(p))
  }

  private def full(k: Map[(String, String), KeyRow] = key, up: ujson.Value = upstream(hex(77))) =
    EvalInputs(
      queue = queue,
      policy = Some(policy),
      manifest = Some(manifest),
      runReport = Some(runReport),
      upstreamReport = Some(up)
    )
  private def run(
      inputs: EvalInputs,
      k: Map[(String, String), KeyRow] = key,
      work: Option[ujson.Value] = None
  ) = Harness.evaluate(gold, k, work, 1L, 100, Some("SYNTHETIC"), inputs)

  test("repair-08: error-analysis rows are traceable to dblp keys and fully listed in Markdown") {
    val m = run(EvalInputs(queue = queue))
    val e = m("researcherResolution")("errorAnalysis")
    val fp = e("falsePositives").arr.head // synthetic row 3: accepted, labeled NON_MATCH
    assertEquals(fp("rowNumber").str, "3")
    assertEquals(fp("leftDblpKey").str, "SYNTHETIC left_dblpKey 3")
    assertEquals(fp("rightDblpKey").str, "SYNTHETIC right_dblpKey 3")
    assertEquals(fp("leftName").str, "SYNTHETIC left_name 3")
    assertEquals(e("falseNegatives").arr.map(_("rowNumber").str).toList, List("5", "6"))
    assertEquals(e("selection").str, Harness.ErrorSelection)
    val md = Harness.markdown(m)
    assert(
      md.contains(
        "1. Queue row 3: left SYNTHETIC left_name 3 (dblp key SYNTHETIC left_dblpKey 3, SYNTHETIC left_dblpUrl 3)"
      ),
      md
    )
    assert(md.contains("#### False negatives (labeled MATCH, not accepted): 2 total, 2 shown"), md)
    assert(md.contains("   - Rules fired: score.accept; reason codes: none."), md)
    assert(md.contains("   - Features: "), md)
    assert(!md.contains("(details in metrics.json)"), md)
  }

  test("repair-09: gold-set counts, UNSURE and unlabeled rows, strata and weighted confusion") {
    val m = run(EvalInputs(queue = queue))
    val g = m("goldSet")
    assertEquals(g("queueRows").num, 9.0)
    assertEquals(g("rowsInGoldFile").num, 9.0)
    assertEquals(g("queueRowsAbsentFromGoldFile").num, 0.0)
    assertEquals(g("labeledRows").num, 8.0)
    assertEquals(Harness.LabelOrder.map(l => g("byLabel")(l).num), List(4.0, 3.0, 1.0))
    assertEquals(g("unsureExcludedFromPrecisionAndRecall").num, 1.0)
    assertEquals(g("unlabeledRows").num, 1.0)
    val md = Harness.markdown(m)
    assert(
      md.contains("- Labeled rows: 8 (MATCH 4, NON_MATCH 3, UNSURE 1); unlabeled rows: 1."),
      md
    )
    assert(md.contains("### By stratum"), md)
    assert(
      md.contains("| path=probabilistic;outcome=rejected;family=initials-surname;band=far | 2 |"),
      md
    )
    assert(md.contains("### Confusion matrix (weighted: estimated population decisions)"), md)
    assert(md.contains(Harness.MetricDefinitions), md)
  }

  test("repair-09: the LLM path states plainly why its metrics are empty") {
    val k = key.updated(
      row8,
      key(row8).copy(
        path = "llm",
        reasonCodes = List("llm-abstain"),
        membershipDecisions = List(("unresolved", "llm"))
      )
    )
    val m = run(full(k), k)
    val l = m("researcherResolution")("llmPath")
    assertEquals(l("labeledRows").num, 1.0)
    assertEquals(l("adjudicatedAcceptOrReject").num, 0.0)
    assertEquals(l("reasonCodes")("llm-abstain").num, 1.0)
    val s = l("statement").str
    assert(s.startsWith("LLM-adjudicated metrics are empty"), s)
    assert(s.contains("adjudicator 'always-abstain' with model id 'none/always-abstain'"), s)
    assert(Harness.markdown(m).contains("### LLM-adjudicated path\n\n" + s), s)
    // an adjudicated row is not reported as empty
    val k2 = k.updated(row8, k(row8).copy(outcome = "accepted"))
    val s2 = run(full(k2), k2)("researcherResolution")("llmPath")("statement").str
    assert(s2.startsWith("1 of 1 labeled llm-path rows were accepted or rejected"), s2)
  }

  test("repair-10: policy, thresholds, features, rules, model and prompt versions, work coverage") {
    val m = run(full(), work = Some(workSummary))
    val c = m("runContext")
    assertEquals(c("policyVersion").str, "er-v2")
    assertEquals(c("thresholds")("accept").num, 0.9)
    assertEquals(c("promptVersion").str, "SYNTHETIC-PROMPT/1")
    assertEquals(c("llmModelId").str, "none/always-abstain")
    assertEquals(c("scope")("venues").num, 16.0)
    assert(c("weightedFeatures").arr.exists(_("feature").str == "given_name_relation"))
    // features in the synthetic key without a policy weight
    assert(c("recordedFeaturesWithoutWeight").arr.map(_.str).contains("name_exact_match"))
    assert(c("rules").arr.exists(_("id").str == "orcid.disagree"))
    val md = Harness.markdown(m)
    assert(md.contains("- Policy: er-v2 (status final)"), md)
    assert(md.contains("prompt SYNTHETIC-PROMPT/1 (sha256 SYNTHETIC-prompt-sha)"), md)
    assert(md.contains("| given_name_relation | 0 to 1: -4; 1 to 2: 1; 2 to open: 3 |"), md)
    assert(md.contains("| orcid.disagree | pair | exact_id | conflict |"), md)
    assert(md.contains("| 0.9 (run) | 0.3 |"), md)
    assert(md.contains("- Exact-link coverage: DOI 0 works (share 0); OpenAlex work 0 works"), md)
    assert(md.contains("landingUrl 1 groups / 2 works"), md)
    assert(md.contains("- Probabilistic work matching: not implemented: SYNTHETIC (finding 11)."))
    // inputs not given are reported as not available, never defaulted
    val bare = run(EvalInputs())
    assertEquals(bare("runContext")("policyVersion"), ujson.Null)
    assertEquals(bare("goldSet")("queueRows"), ujson.Null)
    val bmd = Harness.markdown(bare)
    assert(bmd.contains("- Policy: not available (status not available)"), bmd)
    assert(bmd.contains("Policy file not given: feature definitions not available."), bmd)
  }

  test("repair-11: known limitations are computed from the labeled rows and the run artifacts") {
    val m = run(full())
    val k = m("knownLimitations")
    val st = k("stratumLabelVsDecision")("strata")
    // synthetic row 7: stratum outcome unresolved, decision exact_id/conflict
    val s7 = st("path=probabilistic;outcome=unresolved;family=initials-surname;band=near")
    assertEquals(s7("membershipsWhoseDecisionDiffers").num, 1.0)
    assertEquals(s7("labeledMemberships").num, 3.0) // rows 6, 7, 8 (row 9 is unlabeled)
    assertEquals(s7("decisions")("exact_id/conflict").num, 1.0)
    // synthetic rows 2 and 3: stratum path deterministic, decision probabilistic/accepted
    val s1 = st("path=deterministic;outcome=accepted;family=dblp-base-name;band=far")
    assertEquals(s1("membershipsWhoseDecisionDiffers").num, 2.0)
    assertEquals(k("decisionsNotPairs")("rowsWithSeveralMemberships").num, 0.0)
    assertEquals(k("decisionsNotPairs")("labeledMemberships").num, 8.0)
    val b = k("blockingCoverage")
    assertEquals(b("uncoveredMembers").num, 11.0)
    assertEquals(b("fallbackMaxBlockSize").str, "200")
    assertEquals(b("upstreamRunMatchesErInputRun"), ujson.Bool(true))
    assertEquals(b("erMentionsWithoutCandidates").num, 13.0)
    val md = Harness.markdown(m)
    assert(md.contains("## Known limitations"), md)
    assert(
      md.contains(
        "- Recall gap blocking.SYNTHETIC-gap (run report): firstInitialSurnameGroupsSplitByMiddleInitials 5, dblpNamesInThoseGroups 7."
      ),
      md
    )
    assert(md.contains("11 oversize-block members are in no emitted block"), md)
    assert(md.contains("fallback max block size 200"), md)
    // an upstream report of another run is flagged
    val other = run(full(up = upstream(hex(78))))
    assertEquals(
      other("knownLimitations")("blockingCoverage")("upstreamRunMatchesErInputRun"),
      ujson.Bool(false)
    )
  }

  // ---------------------------------------------------------------------------------------------
  // Phase 5 run 2 repair round (test-engineer findings F1 to F5 and two residual notes)

  private def liveManifest = {
    val x = ujson.read(manifest.render())
    x("config")("adjudicator") = "SYNTHETIC-live-adjudicator"
    x("config")("llmModelId") = "SYNTHETIC/model-1"
    x
  }
  private def llmKey = key.updated(
    row8,
    key(row8).copy(
      path = "llm",
      reasonCodes = List("llm-abstain"),
      membershipDecisions = List(("unresolved", "llm"))
    )
  )

  test("repair-12: the LLM statement follows the run report, not a claim that it never decides") {
    val rr = ujson.Obj(
      "llm" -> ujson.Obj("outcomes" -> ujson.Obj("accepted" -> 3, "rejected" -> 2))
    )
    val m = run(EvalInputs(manifest = Some(liveManifest), runReport = Some(rr)))
    val s = m("researcherResolution")("llmPath")("statement").str
    assert(!s.contains("never accepts or rejects"), s)
    assert(!s.contains("stays unresolved") && !s.contains("stayed unresolved"), s)
    assert(s.contains("records accepted or rejected pairs (outcomes: accepted 3, rejected 2)"), s)
    assert(s.startsWith("LLM-adjudicated metrics are empty: the gold set has no labeled"), s)
    // the always-abstain run report (only unresolved outcomes) supports the unresolved statement
    val s2 = run(full(llmKey), llmKey)("researcherResolution")("llmPath")("statement").str
    assert(s2.contains("records no accepted or rejected pair (outcomes: unresolved 4)"), s2)
    assert(s2.contains("every pair sent to the adjudicator stayed unresolved"), s2)
  }

  test("repair-13: without manifest and run report the adjudicator's behaviour is not asserted") {
    val m = run(EvalInputs(), llmKey)
    val l = m("researcherResolution")("llmPath")
    assertEquals(l("adjudicator"), ujson.Null)
    val s = l("statement").str
    assert(!s.contains("never accepts or rejects"), s)
    assert(!s.contains("unresolved and precision"), s)
    assert(s.contains("not available (run report LLM stage outcomes not given)"), s)
    assert(s.contains(s"adjudicator '${EvalReport.NotAvailable}'"), s)
  }

  test("repair-14: a policy without 'weights' gives not available, never an empty list") {
    val m = run(EvalInputs(policy = Some(ujson.Obj("policyVersion" -> "SYNTHETIC-no-weights"))))
    val c = m("runContext")
    assertEquals(c("weightedFeatures"), ujson.Null)
    assertEquals(c("recordedFeaturesWithoutWeight"), ujson.Null)
    val md = Harness.markdown(m)
    assert(md.contains("Feature definitions not available: the policy file has no 'weights'"), md)
    assert(!md.contains("Features recorded in the review key without a policy weight"), md)
  }

  test("repair-15: a run report without 'knownRecallGaps' gives not available, never no gaps") {
    val m = run(EvalInputs(runReport = Some(ujson.Obj("inputs" -> ujson.Obj("works" -> 10)))))
    assertEquals(m("knownLimitations")("knownRecallGaps"), ujson.Null)
    val md = Harness.markdown(m)
    assert(
      md.contains(
        "- Known recall gaps: not available (the run report has no 'knownRecallGaps' list)."
      ),
      md
    )
  }

  test("repair-16: the policy override is 'none' only when the run manifest records it") {
    def line(m: ujson.Obj) =
      Harness.markdown(m).linesIterator.find(_.startsWith("- Policy:")).getOrElse(fail("none"))
    val noMan = run(EvalInputs(policy = Some(policy)))
    assertEquals(noMan("runContext")("policyOverriddenFrom"), ujson.Null)
    assert(line(noMan).endsWith("overridden from: not available (run manifest not given)."))
    val recorded = run(EvalInputs(manifest = Some(manifest)))
    assert(line(recorded).endsWith("overridden from: none."), line(recorded))
    val without = ujson.read(manifest.render())
    without("config").obj.remove("policyOverriddenFrom")
    val notRecorded = run(EvalInputs(manifest = Some(without)))
    assert(line(notRecorded).endsWith("not available (the run manifest does not record it)."))
  }

  test("repair-17: without a manifest the policy thresholds never stand in for the run's") {
    val m = run(EvalInputs(policy = Some(policy)))
    val c = m("runContext")
    assertEquals(c("thresholds"), ujson.Null)
    assertEquals(c("policyThresholds")("accept").num, 0.9)
    val md = Harness.markdown(m)
    assert(!md.contains(" (run) |"), md)
    assert(md.contains("- Thresholds: run thresholds not available"), md)
    val tl = md.linesIterator.find(_.startsWith("- Thresholds:")).getOrElse(fail(md))
    val policyPart = tl.split("policy file thresholds ", 2)(1)
    assert(policyPart.contains("accept 0.9") && policyPart.contains("reject 0.3"), tl)
    // with the manifest, the manifest's thresholds are the run's
    assert(Harness.markdown(run(full())).contains("| 0.9 (run) | 0.3 |"))
  }

  test("repair-18: a malformed optional JSON input names its role and path") {
    val dir = Files.createTempDirectory("er-eval-malformed-")
    val bad = dir.resolve("bad.json")
    Files.write(bad, "{\"policyVersion\": \"SYNTHETIC\",".getBytes(StandardCharsets.UTF_8))
    val queueP = dir.resolve("queue.jsonl")
    Files.write(
      queueP,
      queue.values.toList
        .sortBy(_("rowNumber").toInt)
        .map(r => ujson.write(ujson.Obj.from(r.map { case (k, v) => k -> ujson.Str(v) })))
        .mkString("", "\n", "\n")
        .getBytes(StandardCharsets.UTF_8)
    )
    for (role <- List("policy", "manifest", "run-report", "upstream-report", "work-summary")) {
      val e = intercept[IllegalArgumentException](
        EvalMain.main(
          Array(
            "--gold",
            resource("gold-valid.csv").toString,
            "--queue",
            queueP.toString,
            "--key",
            resource("review-key.jsonl").toString,
            "--output",
            dir.resolve("out").toString,
            s"--$role",
            bad.toString
          )
        )
      )
      assert(e.getMessage.contains(s"--$role $bad: not valid JSON"), e.getMessage)
    }
  }

  test("repair-19: the run context does not claim that no live model is called") {
    val md = Harness.markdown(run(EvalInputs(manifest = Some(liveManifest))))
    val line = md.linesIterator.find(_.startsWith("- LLM stage:")).getOrElse(fail(md))
    assert(!line.contains("no live model is called"), line)
    assert(line.contains("adjudicator SYNTHETIC-live-adjudicator, model id SYNTHETIC/model-1;"))
  }

  test("repair-10: EvalMain reads the optional inputs and records their sha256 as provenance") {
    val dir = Files.createTempDirectory("er-eval-main-")
    def write(name: String, text: String): Path = {
      val p = dir.resolve(name)
      Files.write(p, text.getBytes(StandardCharsets.UTF_8))
      p
    }
    val goldP = write("gold.csv", goldText)
    val queueP = write(
      "queue.jsonl",
      queue.values.toList
        .sortBy(_("rowNumber").toInt)
        .map(r => ujson.write(ujson.Obj.from(r.map { case (k, v) => k -> ujson.Str(v) })))
        .mkString("", "\n", "\n")
    )
    val manifestP = write("manifest.json", ujson.write(manifest))
    val runReportP = write("run-report.json", ujson.write(runReport))
    val out = dir.resolve("out")
    EvalMain.main(
      Array(
        "--gold",
        goldP.toString,
        "--queue",
        queueP.toString,
        "--key",
        resource("review-key.jsonl").toString,
        "--output",
        out.toString,
        "--manifest",
        manifestP.toString,
        "--run-report",
        runReportP.toString,
        "--bootstrap-reps",
        "50"
      )
    )
    val m = ujson.read(DataFiles.readUtf8(out.resolve("metrics.json")))
    val prov = m("provenance").arr.map(p => p("role").str -> p("sha256").str).toMap
    assertEquals(prov("gold"), DataFiles.sha256OfFile(goldP))
    assertEquals(prov("manifest"), DataFiles.sha256OfFile(manifestP))
    assertEquals(prov.keySet, Set("gold", "queue", "key", "manifest", "run-report"))
    assertEquals(m("goldSet")("queueRows").num, 9.0)
    assertEquals(m("runContext")("promptVersion").str, "SYNTHETIC-PROMPT/1")
    val md = DataFiles.readUtf8(out.resolve("summary.md"))
    assert(md.contains("## Inputs (read only)"), md)
    assert(md.contains("SYNTHETIC left_dblpKey 3"), md)
  }
}
