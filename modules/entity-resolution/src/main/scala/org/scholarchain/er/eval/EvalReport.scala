package org.scholarchain.er.eval

import scala.util.Try

/** Optional read-only inputs of the evaluation report (repairs 08 to 11). Every value comes from a
  * file given on the command line; a missing file or field is reported as not available, never
  * defaulted.
  *
  * @param queue
  *   the label-blind review-queue rows keyed by unordered mention pair (display columns: names and
  *   dblp keys, so evaluation examples are traceable to source records)
  * @param policy
  *   the policy file the run used (feature definitions, thresholds, rules)
  * @param manifest
  *   the run manifest (policy sha, thresholds, prompt, schema and model versions)
  * @param runReport
  *   the run report (scope, LLM stage counts, known recall gaps)
  * @param upstreamReport
  *   the report of the upstream run whose export the ER run read (blocking coverage)
  * @param provenance
  *   `(role, path, sha256)` of every input file
  */
final case class EvalInputs(
    queue: Map[(String, String), Map[String, String]] = Map.empty,
    policy: Option[ujson.Value] = None,
    manifest: Option[ujson.Value] = None,
    runReport: Option[ujson.Value] = None,
    upstreamReport: Option[ujson.Value] = None,
    provenance: List[(String, String, String)] = Nil
)

/** Report sections computed from the labeled rows and the optional inputs. */
object EvalReport {
  import Harness.Labeled

  val NotAvailable = "not available (input not given)"

  /** The fields read from the upstream (Hadoop) run report (repair 24, reviewer R5). */
  val UpstreamFieldsRead: List[String] = List(
    "summary.exportDir",
    "settings.scholarchain.blocking.fallback.maxBlockSize",
    "settings.scholarchain.blocking.maxBlockSize",
    "summary.fallback.uncoveredMembers",
    "summary.fallback.uncoveredPerFamily"
  )

  val UpstreamFormatNote: String =
    "The upstream report is the Hadoop pipeline's run-report.json: an undeclared format written " +
      "by modules/hadoop, not a core contract. It is read best-effort; a missing field or a " +
      s"field of another type is reported as not available. Fields read: ${UpstreamFieldsRead.mkString(", ")}."

  /** `v` at a path of object keys, if every step exists. */
  def at(v: ujson.Value, path: String*): Option[ujson.Value] =
    path.foldLeft(Option(v))((o, k) => o.flatMap(x => Try(x.obj.get(k)).toOption.flatten))

  private def atOpt(v: Option[ujson.Value], path: String*): Option[ujson.Value] =
    v.flatMap(at(_, path*))

  private def orNull(o: Option[ujson.Value]): ujson.Value = o.getOrElse(ujson.Null)

  def jsonType(v: ujson.Value): String = v match {
    case _: ujson.Obj  => "object"
    case _: ujson.Arr  => "array"
    case _: ujson.Str  => "string"
    case _: ujson.Num  => "number"
    case _: ujson.Bool => "boolean"
    case _             => "null"
  }

  private def counts(xs: List[String]): ujson.Obj =
    ujson.Obj.from(xs.groupBy(identity).toList.sortBy(_._1).map { case (k, v) =>
      k -> ujson.Num(v.size)
    })

  /** Gold-set size and label counts (repair 09). UNSURE labels are counted and excluded from
    * precision and recall; unlabeled rows are counted.
    */
  def goldSet(gold: GoldSet, queue: Map[(String, String), Map[String, String]]): ujson.Obj = {
    val by = gold.labels.groupBy(_.label).view.mapValues(_.size).toMap
    val fileRows = gold.labels.size + gold.unlabeledRows
    ujson.Obj(
      "queueRows" -> (if (queue.isEmpty) ujson.Null else ujson.Num(queue.size)),
      "rowsInGoldFile" -> fileRows,
      "queueRowsAbsentFromGoldFile" -> (if (queue.isEmpty) ujson.Null
                                        else ujson.Num(queue.size - fileRows)),
      "labeledRows" -> gold.labels.size,
      "byLabel" -> ujson.Obj.from(Harness.LabelOrder.map(l => l -> ujson.Num(by.getOrElse(l, 0)))),
      "unsureExcludedFromPrecisionAndRecall" -> by.getOrElse("UNSURE", 0),
      "unlabeledRows" -> gold.unlabeledRows,
      "distinctLabelers" -> gold.labels.map(_.labeler).distinct.size
    )
  }

  /** Display columns of a labeled row that trace it to its dblp source records (repair 08). */
  def trace(
      l: Labeled,
      queue: Map[(String, String), Map[String, String]]
  ): List[(String, ujson.Value)] = {
    val q = queue.get((l.gold.leftMentionId, l.gold.rightMentionId))
    def f(k: String): ujson.Value = q.flatMap(_.get(k)).fold[ujson.Value](ujson.Null)(ujson.Str(_))
    List(
      "rowNumber" -> f("rowNumber"),
      "leftName" -> f("left_name"),
      "leftDblpKey" -> f("left_dblpKey"),
      "leftDblpUrl" -> f("left_dblpUrl"),
      "rightName" -> f("right_name"),
      "rightDblpKey" -> f("right_dblpKey"),
      "rightDblpUrl" -> f("right_dblpUrl")
    )
  }

  /** The LLM path (repair 09): how many labeled rows the adjudicator decided, and why the
    * LLM-adjudicated metrics are empty when it decided none.
    */
  def llmPath(labeled: List[Labeled], inputs: EvalInputs): ujson.Obj = {
    val rows = labeled.filter(_.key.path == "llm")
    val decided = rows.filter(l => l.key.outcome == "accepted" || l.key.outcome == "rejected")
    // repair 27 (F7 related): a non-string manifest value is quoted as JSON text, never thrown on
    val adjudicator = atOpt(inputs.manifest, "config", "adjudicator").map(text)
    val model = atOpt(inputs.manifest, "config", "llmModelId").map(text)
    val reasons = rows.flatMap(_.key.reasonCodes)
    // repairs 12 and 13: what the adjudicator did in the run is stated only from the run report's
    // LLM stage outcomes, never from the adjudicator's name, and is not available without them
    val runOutcomes = atOpt(inputs.runReport, "llm", "outcomes").filter(o => Try(o.obj).isSuccess)
    // repair 26 (test-engineer F6): a count that is not a finite non-negative number makes the
    // outcomes unusable ("not available"); it is never read as zero decisions
    def validCount(v: ujson.Value) = v match {
      case ujson.Num(n) => !n.isNaN && !n.isInfinite && n >= 0
      case _            => false
    }
    val malformedOutcomes = runOutcomes.exists(_.obj.values.exists(v => !validCount(v)))
    val runDecided = runOutcomes
      .filterNot(_ => malformedOutcomes)
      .map(_.obj.exists { case (k, v) =>
        (k == "accepted" || k == "rejected") && v.num > 0
      })
    // repair 29 (reviewer finding 6, human ruling): the run report's recorded counts only. Each
    // count is a whole non-negative number or "not available" (the F6 rule); nothing is read from
    // the adjudicator's name or model id. An outcome key absent from valid outcome counts counts as
    // 0 only when those counts add up to the pairs sent (every sent pair is accounted for).
    val llmStage = atOpt(inputs.runReport, "llm").filter(o => Try(o.obj).isSuccess)
    def wholeCount(v: Option[ujson.Value]): Option[Long] = v.collect {
      case ujson.Num(n) if !n.isNaN && !n.isInfinite && n >= 0 && n == math.rint(n) => n.toLong
    }
    val sent = wholeCount(atOpt(llmStage, "pairsSentToAdjudicator"))
    val abstained = wholeCount(atOpt(llmStage, "reasons", "llm-abstain"))
    val validOutcomes = runOutcomes.filterNot(_ => malformedOutcomes)
    // Phase 16 (test-engineer F9, part of reviewer M2): the outcome counts account for every sent
    // pair only when each of them is a whole count (the rule above); a fractional count never lets
    // an absent key read as 0. M2's cached-pairs part (outcomes include cache hits, pairs sent do
    // not) is not changed here.
    val allSentAccounted =
      sent.exists(n =>
        validOutcomes.exists(o =>
          o.obj.values.forall(v => wholeCount(Some(v)).isDefined) &&
            o.obj.values.map(_.num).sum == n.toDouble
        )
      )
    def outcomeCount(k: String): Option[Long] = validOutcomes.flatMap(o =>
      o.obj.get(k) match {
        case Some(v)                  => wholeCount(Some(v))
        case None if allSentAccounted => Some(0L)
        case None                     => None
      }
    )
    val accepted = outcomeCount("accepted")
    val rejected = outcomeCount("rejected")
    def shown(c: Option[Long]) = c.fold("not available")(_.toString)
    val countSentence = llmStage.fold("") { _ =>
      val sentPart = sent.fold("the number of band pairs sent to the adjudicator is not available")(
        n => s"$n band pairs were sent to the adjudicator"
      )
      val abstainPart = (abstained, sent) match {
        case (Some(a), Some(n)) if a == n => s"all $n abstained (reason llm-abstain $a)"
        case (Some(a), _)                 => s"$a abstained (reason llm-abstain)"
        case (None, _)                    => "the llm-abstain count is not available"
      }
      val absent =
        validOutcomes.exists(o => !o.obj.contains("accepted") || !o.obj.contains("rejected"))
      val note =
        if (absent && accepted.isDefined && rejected.isDefined)
          s" (an outcome absent from the recorded outcome counts, which account for all ${shown(sent)} pairs sent, is 0)"
        else ""
      val decidedPart = (accepted, rejected) match {
        case (Some(a), Some(r)) if a == 0 && r == 0 =>
          s"0 were accepted or rejected$note, so LLM-adjudicated metrics are empty"
        case (Some(a), Some(r)) => s"$a were accepted and $r were rejected$note"
        case (a, r)             =>
          s"the accepted count is ${shown(a)} and the rejected count is ${shown(r)}"
      }
      s" Run report LLM stage counts: $sentPart; $abstainPart; $decidedPart."
    }
    val statement =
      if (decided.nonEmpty)
        s"${decided.size} of ${rows.size} labeled llm-path rows were accepted or rejected by the adjudicator; their metrics are in the path llm row."
      else {
        val head =
          if (rows.isEmpty)
            "LLM-adjudicated metrics are empty: the gold set has no labeled llm-path row."
          else
            s"LLM-adjudicated metrics are empty: none of the ${rows.size} labeled llm-path rows was accepted or rejected by the adjudicator (their outcomes: ${rows.map(_.key.outcome).distinct.sorted.mkString(", ")}; reason codes: ${reasons.distinct.sorted.mkString(", ")})."
        val used =
          s" The run used adjudicator '${adjudicator.getOrElse(NotAvailable)}' with model id '${model.getOrElse(NotAvailable)}'."
        val evidence = runDecided match {
          case Some(false) =>
            s" The run report's LLM stage records no accepted or rejected pair (outcomes: ${text(runOutcomes.get)}), so every pair sent to the adjudicator stayed unresolved and precision and recall of LLM decisions cannot be estimated in this phase."
          case Some(true) =>
            s" The run report's LLM stage records accepted or rejected pairs (outcomes: ${text(runOutcomes.get)}), but none of them is a labeled llm-path row, so precision and recall of LLM decisions cannot be estimated from this gold set."
          case None if malformedOutcomes =>
            s" Whether the adjudicator accepted or rejected any pair in the run is not available (the run report's LLM stage outcome counts are not all non-negative numbers: ${text(runOutcomes.get)}), so precision and recall of LLM decisions cannot be estimated from this gold set."
          case None =>
            " Whether the adjudicator accepted or rejected any pair in the run is not available (run report LLM stage outcomes not given), so precision and recall of LLM decisions cannot be estimated from this gold set."
        }
        val tail =
          if (rows.isEmpty) ""
          else " The path llm row shows only their coverage and unresolved rate."
        head + used + evidence + countSentence + tail
      }
    ujson.Obj(
      "labeledRows" -> rows.size,
      "adjudicatedAcceptOrReject" -> decided.size,
      "outcomes" -> counts(rows.map(_.key.outcome)),
      "reasonCodes" -> counts(reasons),
      "adjudicator" -> orNull(adjudicator.map(ujson.Str(_))),
      "llmModelId" -> orNull(model.map(ujson.Str(_))),
      "runLlmStage" -> orNull(atOpt(inputs.runReport, "llm")),
      "statement" -> statement,
      // repair 29: the recorded counts the statement uses (null: not available)
      "runCounts" -> ujson.Obj(
        "pairsSentToAdjudicator" -> orNull(sent.map(n => ujson.Num(n.toDouble))),
        "llmAbstain" -> orNull(abstained.map(n => ujson.Num(n.toDouble))),
        "accepted" -> orNull(accepted.map(n => ujson.Num(n.toDouble))),
        "rejected" -> orNull(rejected.map(n => ujson.Num(n.toDouble))),
        "rule" -> RunCountsRule
      )
    )
  }

  val RunCountsRule: String =
    "From the run report's llm section only: pairsSentToAdjudicator, reasons.llm-abstain, outcomes.accepted and outcomes.rejected. A count that is missing or not a whole non-negative number is not available; an outcome absent from valid outcome counts is 0 only when those counts add up to the pairs sent. Nothing is inferred from the adjudicator's name or model id."

  val ReplacedLabel: String =
    "These replaced files are stale outputs of an earlier run into this directory; they are not a source of any number in this report."

  private def stratumField(s: String, f: String): String = Harness.fieldOf(s, f)

  /** Known limitations (repair 11), each computed from the labeled rows or an input file. */
  def limitations(labeled: List[Labeled], inputs: EvalInputs): ujson.Obj = {
    val ms = labeled.flatMap(l => l.key.decisions.map(d => (d, l)))
    val mismatched = ms
      .filter { case (d, _) =>
        stratumField(d.stratum, "outcome") != d.outcome || stratumField(d.stratum, "path") != d.path
      }
      .groupBy(_._1.stratum)
      .toList
      .sortBy(_._1)
      .map { case (s, xs) =>
        s -> ujson.Obj(
          "labeledMemberships" -> ms.count(_._1.stratum == s),
          "membershipsWhoseDecisionDiffers" -> xs.size,
          "decisions" -> counts(xs.map { case (d, _) => s"${d.path}/${d.outcome}" })
        )
      }
    val multi = labeled.count(_.key.memberships.size > 1)
    // repair 15: a run report without a 'knownRecallGaps' list is not available, never "no gaps"
    val gaps = atOpt(inputs.runReport, "knownRecallGaps").flatMap(g => Try(g.arr.toList).toOption)
    val up = inputs.upstreamReport
    // repair 27 (F7 related): a value that is not a string is not available, never thrown on
    val inputRun = atOpt(inputs.manifest, "inputRun", "runId").flatMap(v => Try(v.str).toOption)
    val exportDir = atOpt(up, "summary", "exportDir").flatMap(v => Try(v.str).toOption)
    val upstreamMatches: ujson.Value = (inputRun, exportDir) match {
      case (Some(r), Some(e)) => ujson.Bool(e.endsWith("run=" + r.stripPrefix("run:v1:")))
      case _                  => ujson.Null
    }
    ujson.Obj(
      "stratumLabelVsDecision" -> ujson.Obj(
        "description" -> "m3: a stratum name records the path and outcome of the sampling frame; memberships whose own decision differs are listed. Metrics always use each membership's own decision, so only the stratum names are affected.",
        "strata" -> ujson.Obj.from(mismatched)
      ),
      "decisionsNotPairs" -> ujson.Obj(
        "description" -> "m4: weighted metrics count decisions (stratum memberships), not distinct mention pairs; a pair that is both a candidate and a dblp-prior pair is counted once per membership.",
        "labeledRows" -> labeled.size,
        "labeledMemberships" -> ms.size,
        "rowsWithSeveralMemberships" -> multi
      ),
      "knownRecallGaps" -> gaps.fold[ujson.Value](ujson.Null)(ujson.Arr.from(_)),
      "blockingCoverage" -> ujson.Obj(
        // repair 24 (reviewer R5): the upstream report's format is not a core contract
        "upstreamReportFormat" -> UpstreamFormatNote,
        "upstreamReportFieldsRead" -> ujson.Arr.from(UpstreamFieldsRead.map(ujson.Str(_))),
        "upstreamRunMatchesErInputRun" -> upstreamMatches,
        "fallbackMaxBlockSize" -> orNull(
          atOpt(up, "settings", "scholarchain.blocking.fallback.maxBlockSize")
        ),
        "maxBlockSize" -> orNull(atOpt(up, "settings", "scholarchain.blocking.maxBlockSize")),
        "uncoveredMembers" -> orNull(atOpt(up, "summary", "fallback", "uncoveredMembers")),
        "uncoveredPerFamily" -> orNull(atOpt(up, "summary", "fallback", "uncoveredPerFamily")),
        "erMentionsWithoutCandidates" -> orNull(
          atOpt(inputs.runReport, "inputs", "mentionsWithoutCandidates")
        )
      )
    )
  }

  /** Policy, thresholds, feature definitions, rules, prompt and model versions, and scope (repair
    * 10), from the policy file, the run manifest and the run report.
    */
  def runContext(labeled: List[Labeled], inputs: EvalInputs): ujson.Obj = {
    val p = inputs.policy
    val cfg = atOpt(inputs.manifest, "config")
    // repair 14: a policy without a 'weights' list is not available, never "no weighted feature"
    val weighted = atOpt(p, "weights").flatMap(w => Try(w.arr.toList).toOption)
    val recorded = labeled.flatMap(_.key.features.map(_._1)).distinct.sorted
    def isGiven(o: Option[ujson.Value]) = ujson.Bool(o.isDefined)
    ujson.Obj(
      "inputsGiven" -> ujson.Obj(
        "policy" -> isGiven(p),
        "manifest" -> isGiven(inputs.manifest),
        "runReport" -> isGiven(inputs.runReport),
        "upstreamReport" -> isGiven(inputs.upstreamReport)
      ),
      "policyVersion" -> orNull(atOpt(p, "policyVersion")),
      "policyStatus" -> orNull(atOpt(p, "status")),
      "policySha256InManifest" -> orNull(cfg.flatMap(at(_, "policySha256"))),
      "policyOverriddenFrom" -> orNull(cfg.flatMap(at(_, "policyOverriddenFrom"))),
      // repair 16: whether the manifest records the override field at all (null without manifest)
      "policyOverriddenFromRecorded" -> (if (inputs.manifest.isEmpty) ujson.Null
                                         else
                                           ujson.Bool(
                                             cfg.exists(x =>
                                               Try(x.obj.contains("policyOverriddenFrom"))
                                                 .getOrElse(false)
                                             )
                                           )),
      "priorLogOdds" -> orNull(atOpt(p, "priorLogOdds")),
      // repair 17: the run's thresholds come from the run manifest only; the policy file's
      // thresholds are reported apart and never stand in for the run's
      "thresholds" -> orNull(cfg.flatMap(at(_, "thresholds"))),
      "policyThresholds" -> orNull(atOpt(p, "thresholds")),
      "promptVersion" -> orNull(cfg.flatMap(at(_, "promptVersion"))),
      "promptSha256" -> orNull(cfg.flatMap(at(_, "promptSha256"))),
      "outputSchemaVersion" -> orNull(cfg.flatMap(at(_, "outputSchemaVersion"))),
      "outputSchemaSha256" -> orNull(cfg.flatMap(at(_, "outputSchemaSha256"))),
      "llmModelId" -> orNull(cfg.flatMap(at(_, "llmModelId"))),
      "adjudicator" -> orNull(cfg.flatMap(at(_, "adjudicator"))),
      "nameKeyVersion" -> orNull(cfg.flatMap(at(_, "nameKeyVersion"))),
      "candidateFeatureSet" -> orNull(cfg.flatMap(at(_, "candidateFeatureSet"))),
      "runId" -> orNull(atOpt(inputs.manifest, "runId")),
      "codeVersion" -> orNull(atOpt(inputs.manifest, "codeVersion")),
      "decisionTime" -> orNull(atOpt(inputs.manifest, "decisionTime")),
      "inputRun" -> orNull(atOpt(inputs.manifest, "inputRun")),
      "scope" -> orNull(atOpt(inputs.runReport, "inputs")),
      "weightedFeatures" -> weighted.fold[ujson.Value](ujson.Null)(ujson.Arr.from(_)),
      "recordedFeaturesWithoutWeight" -> weighted.fold[ujson.Value](ujson.Null) { ws =>
        val names = ws.flatMap(w => at(w, "feature").flatMap(v => Try(v.str).toOption)).toSet
        ujson.Arr.from(recorded.filterNot(names).map(ujson.Str(_)))
      },
      "rules" -> orNull(atOpt(p, "rules").map { rs =>
        // repair 25 (reviewer R7): a 'rules' field that is present but not an array stops the run
        // with the role and field named (the pinned behaviour: stop, nothing written)
        val list = rs match {
          case a: ujson.Arr => a.value
          case other        =>
            throw new IllegalArgumentException(
              s"--policy: the policy file's 'rules' field is present but is not an array (found ${jsonType(other)}: ${other.render().take(80)}); the evaluation stops and writes nothing"
            )
        }
        ujson.Arr.from(list.map { r =>
          ujson.Obj(
            "id" -> orNull(at(r, "id")),
            "kind" -> orNull(at(r, "kind")),
            "path" -> orNull(at(r, "path")),
            "outcome" -> orNull(at(r, "outcome"))
          )
        })
      })
    )
  }

  def provenanceJson(inputs: EvalInputs): ujson.Arr =
    ujson.Arr.from(inputs.provenance.map { case (role, path, sha) =>
      ujson.Obj("role" -> role, "path" -> path, "sha256" -> sha)
    })

  // ---------------------------------------------------------------------------------------------
  // Markdown sections (all values read back from the metrics object)

  /** A JSON value as compact Markdown text; null is "not available". */
  def text(v: ujson.Value): String = v match {
    case ujson.Null                        => "not available"
    case ujson.Str(s)                      => s
    case ujson.Num(n) if n == math.rint(n) => n.toLong.toString
    case ujson.Num(n)                      => n.toString
    case ujson.Bool(b)                     => b.toString
    case o: ujson.Obj if o.value.isEmpty   => "none"
    case a: ujson.Arr if a.value.isEmpty   => "none"
    case o: ujson.Obj                      =>
      o.value.map { case (k, x) => s"$k ${text(x)}" }.mkString(", ")
    case a: ujson.Arr => a.value.map(text).mkString(", ")
  }

  private def get(v: ujson.Value, path: String*): ujson.Value = at(v, path*).getOrElse(ujson.Null)

  private def cell(s: String): String = s.replace("|", "\\|").replace("\n", " ").replace("\r", " ")

  def goldSetMd(m: ujson.Value): String = {
    val g = m("goldSet")
    val by = g("byLabel")
    s"""## Gold set
       |
       |- Review-queue rows: ${text(g("queueRows"))}; rows in the gold file: ${text(
        g("rowsInGoldFile")
      )}; queue rows absent from the gold file: ${text(g("queueRowsAbsentFromGoldFile"))}.
       |- Labeled rows: ${text(g("labeledRows"))} (MATCH ${text(by("MATCH"))}, NON_MATCH ${text(
        by("NON_MATCH")
      )}, UNSURE ${text(by("UNSURE"))}); unlabeled rows: ${text(g("unlabeledRows"))}.
       |- UNSURE labels are excluded from precision, recall and coverage and counted here: ${text(
        g("unsureExcludedFromPrecisionAndRecall")
      )}. They stay in the unresolved-rate denominator (all labeled units).
       |- Distinct labelers in the gold file: ${text(
        g("distinctLabelers")
      )}. Labels are human decisions read from the human-owned gold file; the harness never writes it.
       |
       |""".stripMargin
  }

  def provenanceMd(m: ujson.Value): String = {
    val rows = m("provenance").arr
    if (rows.isEmpty) ""
    else
      "## Inputs (read only)\n\n| Role | Path | sha256 |\n|---|---|---|\n" +
        rows
          .map(r => s"| ${r("role").str} | ${cell(r("path").str)} | ${r("sha256").str} |")
          .mkString("\n") + "\n\n" +
        // repair 24 (reviewer R5): the upstream report is not a core contract
        (if (rows.exists(_("role").str == "upstream-report"))
           "upstream-report: the Hadoop pipeline's run report, an undeclared format owned by modules/hadoop (not a core contract), read best-effort; the fields read are listed under Known limitations, blocking coverage.\n\n"
         else "")
  }

  /** The output directory and the earlier outputs this run replaced (repair 23, reviewer R4). */
  def outputsMd(m: ujson.Value): String = {
    val o = m("outputs")
    val replaced = get(o, "replaced") match {
      case a: ujson.Arr if a.value.nonEmpty =>
        a.value
          .map(r => s"${text(get(r, "file"))} sha256 ${text(get(r, "previousSha256"))}")
          .mkString("; ")
      case _: ujson.Arr => "none (no earlier metrics.json or summary.md in the directory)"
      case v            => text(v)
    }
    // repair 30 (human ruling on stale outputs): replaced files are labeled stale, never sources
    val label = get(o, "replaced") match {
      case a: ujson.Arr if a.value.nonEmpty => s" $ReplacedLabel"
      case _                                => ""
    }
    s"""## Outputs
       |
       |- Directory: ${cell(text(get(o, "directory")))}. ${text(get(o, "overwriteRule"))}
       |- Replaced earlier outputs (sha256 before this run): $replaced.$label
       |
       |""".stripMargin
  }

  def runContextMd(m: ujson.Value): String = {
    val c = m("runContext")
    def thresholdText(t: ujson.Value): String = t match {
      case o: ujson.Obj =>
        o.value
          .filterNot(_._1 == "rationale")
          .map { case (k, v) => s"$k ${text(v)}" }
          .mkString(", ")
      case v => text(v)
    }
    // repair 17: without the run manifest the run's thresholds are not available; the policy
    // file's thresholds are shown apart, never as the run's
    val thresholds = (c("thresholds"), get(c, "policyThresholds")) match {
      case (ujson.Null, ujson.Null) => text(ujson.Null)
      case (ujson.Null, pt)         =>
        s"run thresholds not available (run manifest not given or without thresholds); policy file thresholds ${thresholdText(pt)}"
      case (t, _) => thresholdText(t)
    }
    // repair 16: "none" only when the run manifest records that the policy was not overridden
    val overridden = (c("policyOverriddenFrom"), get(c, "policyOverriddenFromRecorded")) match {
      case (ujson.Null, ujson.Bool(true))  => "none"
      case (ujson.Null, ujson.Bool(false)) =>
        "not available (the run manifest does not record it)"
      case (ujson.Null, _) => "not available (run manifest not given)"
      case (v, _)          => text(v)
    }
    val scope = c("scope") match {
      case o: ujson.Obj =>
        List(
          "venues",
          "works",
          "authorMentions",
          "dblpNames",
          "matchCandidates",
          "mentionsWithCandidates",
          "mentionsWithoutCandidates"
        ).map(k => s"$k ${text(get(o, k))}").mkString(", ")
      case v => text(v)
    }
    s"""## Run context
       |
       |- Policy: ${text(c("policyVersion"))} (status ${text(
        c("policyStatus")
      )}); policy sha256 recorded in the run manifest: ${text(
        c("policySha256InManifest")
      )}; overridden from: $overridden.
       |- Thresholds: $thresholds; prior log-odds ${text(c("priorLogOdds"))}.
       |- LLM stage: adjudicator ${text(c("adjudicator"))}, model id ${text(
        c("llmModelId")
      )}; prompt ${text(
        c("promptVersion")
      )} (sha256 ${text(c("promptSha256"))}); output schema ${text(
        c("outputSchemaVersion")
      )} (sha256 ${text(c("outputSchemaSha256"))}).
       |- Run: ${text(c("runId"))}, code version ${text(c("codeVersion"))}, decision time ${text(
        c("decisionTime")
      )}; name key ${text(c("nameKeyVersion"))}; candidate feature set ${text(
        c("candidateFeatureSet")
      )}.
       |- Input run: ${text(get(c, "inputRun", "jobName"))} ${text(
        get(c, "inputRun", "runId")
      )}, snapshot ${text(get(c, "inputRun", "snapshotId"))}.
       |- Scope (run report inputs): $scope.
       |
       |""".stripMargin
  }

  def featuresMd(m: ujson.Value): String = {
    val c = m("runContext")
    val sb = new StringBuilder("## Feature definitions and rules (from the policy file)\n\n")
    c("weightedFeatures") match {
      case a: ujson.Arr =>
        sb.append(
          "| Feature | Bins (min to max: log-odds weight) | Scaled by source reliability | Definition |\n|---|---|---|---|\n"
        )
        a.value.foreach { w =>
          val bins = get(w, "bins") match {
            case b: ujson.Arr =>
              b.value
                .map(x =>
                  s"${text(get(x, "min"))} to ${text(get(x, "max")).replace("not available", "open")}: ${text(get(x, "weight"))}"
                )
                .mkString("; ")
            case v => text(v)
          }
          sb.append(
            s"| ${text(get(w, "feature"))} | $bins | ${text(get(w, "scaledBySourceReliability"))} | ${cell(text(get(w, "rationale")))} |\n"
          )
        }
        sb.append(
          s"\nFeatures recorded in the review key without a policy weight (inputs to rules or reporting only): ${text(c("recordedFeaturesWithoutWeight"))}.\n\n"
        )
      case _ if get(c, "inputsGiven", "policy") == ujson.Bool(true) =>
        sb.append(
          "Feature definitions not available: the policy file has no 'weights' list.\n\n"
        )
      case _ => sb.append("Policy file not given: feature definitions not available.\n\n")
    }
    c("rules") match {
      case a: ujson.Arr =>
        sb.append("| Rule | Kind | Path | Outcome |\n|---|---|---|---|\n")
        a.value.foreach(r =>
          sb.append(
            s"| ${text(r("id"))} | ${text(r("kind"))} | ${text(r("path"))} | ${text(r("outcome"))} |\n"
          )
        )
        sb.append("\n")
      case _ => ()
    }
    sb.toString
  }

  def llmMd(m: ujson.Value): String = {
    val l = m("researcherResolution")("llmPath")
    s"""### LLM-adjudicated path
       |
       |${l("statement").str}
       |
       |Labeled llm-path rows: ${text(
        l("labeledRows")
      )}; accepted or rejected by the adjudicator: ${text(
        l("adjudicatedAcceptOrReject")
      )}; outcomes: ${text(l("outcomes"))}; reason codes: ${text(
        l("reasonCodes")
      )}. Run LLM stage: ${text(
        get(l, "runLlmStage", "outcomes")
      )} (reasons ${text(get(l, "runLlmStage", "reasons"))}; pairs sent ${text(
        get(l, "runLlmStage", "pairsSentToAdjudicator")
      )} of ${text(get(l, "runLlmStage", "bandCandidates"))} band candidates).
       |
       |""".stripMargin
  }

  def errorsMd(m: ujson.Value): String = {
    val e = m("researcherResolution")("errorAnalysis")
    val sb = new StringBuilder("### Error analysis\n\n")
    sb.append(s"Selection: ${e("selection").str}.\n\n")
    def side(x: ujson.Value, p: String) =
      s"${text(x(p + "Name"))} (dblp key ${text(x(p + "DblpKey"))}, ${text(x(p + "DblpUrl"))})"
    def block(title: String, list: ujson.Value, total: ujson.Value): Unit = {
      sb.append(s"#### $title: ${text(total)} total, ${list.arr.size} shown\n\n")
      if (list.arr.isEmpty) sb.append("None.\n\n")
      list.arr.zipWithIndex.foreach { case (x, i) =>
        val feats = x("features").obj.map { case (k, v) => s"$k=${text(v)}" }.mkString(", ")
        sb.append(
          s"""${i + 1}. Queue row ${text(x("rowNumber"))}: left ${side(x, "left")}; right ${side(
              x,
              "right"
            )}.
             |   - Mentions ${x("leftMentionId").str} / ${x("rightMentionId").str}; candidate ${x(
              "candidateId"
            ).str}.
             |   - Label ${x("label").str} (evidence: ${text(x("evidence"))}); note: ${cell(
              x("note").str
            )}
             |   - Decision ${x("outcome").str}, path ${x("matchPath").str}, family ${x(
              "family"
            ).str}, match probability ${text(x("matchProbability"))}.
             |   - Rules fired: ${text(x("rulesFired"))}; reason codes: ${text(x("reasonCodes"))}.
             |   - Features: ${if (feats.isEmpty) "none" else feats}.
             |""".stripMargin
        )
      }
      sb.append("\n")
    }
    block(
      "False positives (accepted, labeled NON_MATCH)",
      e("falsePositives"),
      e("falsePositivesTotal")
    )
    block(
      "False negatives (labeled MATCH, not accepted)",
      e("falseNegatives"),
      e("falseNegativesTotal")
    )
    sb.toString
  }

  def workMd(m: ujson.Value): String = {
    val w = m("workResolution")
    val s = w("summary")
    val sb = new StringBuilder("## Work resolution (exact identifiers only)\n\n")
    sb.append(w("labels").str).append(".\n\n")
    if (s == ujson.Null) sb.append("Work-resolution summary not given.\n\n")
    else {
      def cov(k: String) =
        s"${text(get(s, k, "works"))} works (share ${text(get(s, k, "share"))})"
      val collisions = get(s, "dblpIdentifierCollisions") match {
        case o: ujson.Obj =>
          o.value
            .map { case (k, v) =>
              s"$k ${text(get(v, "groups"))} groups / ${text(get(v, "works"))} works"
            }
            .mkString("; ")
        case v => text(v)
      }
      val avail = get(s, "identifierAvailability") match {
        case o: ujson.Obj =>
          o.value
            .map {
              case (k, v: ujson.Obj) =>
                s"$k ${text(get(v, "works"))} works (share ${text(get(v, "share"))})"
              case (k, v) => s"$k: ${text(v)}"
            }
            .mkString("; ")
        case v => text(v)
      }
      sb.append(
        s"""- Subset works: ${text(
            get(s, "subsetWorks")
          )}; external-ID link directory present: ${text(
            get(s, "externalIdLinkDirectoryPresent")
          )}.
           |- Exact-link coverage: DOI ${cov("exactDoiLinkCoverage")}; OpenAlex work ${cov(
            "exactOpenAlexWorkLinkCoverage"
          )}; links outside the subset: ${text(get(s, "workLinksOutsideSubset"))}.
           |- Conflicts: J04 conflicts directory given: ${text(
            get(s, "j04ConflictsDirectoryGiven")
          )}; J04 conflicts by reason: ${text(
            get(s, "j04ConflictsByReason")
          )}; identifiers shared by several dblp works (each recorded as a conflict decision, never merged): $collisions.
           |- Identifier availability: $avail.
           |- Probabilistic work matching: ${text(get(s, "probabilisticWorkMatching"))}.
           |
           |""".stripMargin
      )
    }
    sb.toString
  }

  def limitationsMd(m: ujson.Value): String = {
    val k = m("knownLimitations")
    val sb = new StringBuilder("## Known limitations\n\n")
    val st = k("stratumLabelVsDecision")
    sb.append(s"- ${st("description").str}\n")
    if (st("strata").obj.isEmpty) sb.append("  - No labeled membership differs.\n")
    st("strata").obj.foreach { case (s, v) =>
      sb.append(
        s"  - $s: ${text(v("membershipsWhoseDecisionDiffers"))} of ${text(v("labeledMemberships"))} labeled memberships have decision ${text(v("decisions"))}.\n"
      )
    }
    val d = k("decisionsNotPairs")
    sb.append(
      s"- ${d("description").str} In this gold set: ${text(d("labeledRows"))} labeled rows, ${text(d("labeledMemberships"))} memberships, ${text(d("rowsWithSeveralMemberships"))} rows with several memberships.\n"
    )
    k("knownRecallGaps") match {
      case a: ujson.Arr =>
        a.value.foreach {
          case g: ujson.Obj =>
            val nums =
              g.value.collect { case (n, v: ujson.Num) => s"$n ${text(v)}" }.mkString(", ")
            sb.append(
              s"- Recall gap ${text(get(g, "id"))} (run report): $nums. ${text(get(g, "description"))}\n"
            )
          // repair 27 (test-engineer F7): an element that is not an object never stops the run
          case other =>
            sb.append(
              s"- Recall gap (run report): not available (a 'knownRecallGaps' element is a ${jsonType(other)}, not an object: ${cell(other.render().take(200))}).\n"
            )
        }
      case _ if get(m, "runContext", "inputsGiven", "runReport") == ujson.Bool(true) =>
        sb.append(
          "- Known recall gaps: not available (the run report has no 'knownRecallGaps' list).\n"
        )
      case _ => sb.append("- Known recall gaps: run report not given.\n")
    }
    val b = k("blockingCoverage")
    // repair 24 (reviewer R5): the upstream report is read best-effort from a non-core format
    val format = get(b, "upstreamReportFormat") match {
      case ujson.Str(s) => s" $s"
      case _            => ""
    }
    sb.append(
      s"- Blocking coverage (upstream run report): ${text(b("uncoveredMembers"))} oversize-block members are in no emitted block and get no match candidates (per family: ${text(
          b("uncoveredPerFamily")
        )}); fallback max block size ${text(b("fallbackMaxBlockSize"))}, max block size ${text(
          b("maxBlockSize")
        )}. They keep only their dblp-prior cluster. Upstream run is the ER input run: ${text(
          b("upstreamRunMatchesErInputRun")
        )}. ER run report, mentions without any candidate: ${text(b("erMentionsWithoutCandidates"))}.$format\n\n"
    )
    sb.toString
  }
}
