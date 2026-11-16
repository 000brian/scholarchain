package org.scholarchain.rag.runtime

import scala.collection.mutable

import org.scholarchain.core._
import org.scholarchain.core.config.LlmProvider

/** Output of stage 1. `source` is `llm` only when a live model's draft passed validation; the fake
  * provider and the fallback are `rule`.
  */
final case class PlannerOutput(
    draft: PlanDraft,
    source: PlanSource,
    attempts: List[ModelAttempt],
    fallbackReason: Option[String],
    millisInFallback: Option[Long]
)

/** Stage 1, QueryPlanner: the model returns a plan draft (prompt `query-planner-v1`, schema
  * `query-plan-draft-v1`). A draft that fails the allowlist validation gets at most
  * `llm.maxStructuredRetries` retries, then [[RulePlanner]] is used. The only output is a typed
  * plan; user text never becomes query text.
  */
final class QueryPlanner(
    model: LanguageModel,
    prompt: PromptTemplate,
    rule: RulePlanner,
    ctx: RuntimeContext
) {
  def plan(question: String): PlannerOutput = {
    val b = ctx.bounds
    val values = Map(
      "OPERATIONS" -> QueryOperation.values.map(_.wire).mkString(", "),
      "MAX_NAMES" -> b.maxResearcherNames.toString,
      "WINDOWS" -> ctx.windowLabels.mkString(", "),
      "WINDOW_YEARS" -> ctx.windows
        .map(w => s"${w.label} = ${w.startYear}-${w.endYear}")
        .mkString("; "),
      "METRICS" -> MetricNames.ranked.mkString(", "),
      "MAX_LIMIT" -> b.rankingTopK.toString,
      "MAX_DEPTH" -> b.maxPathDepth.toString,
      "QUESTION" -> Prompts.asData(question, QueryRequest.MaxQuestionChars)
    )
    val attempts = List.newBuilder[ModelAttempt]
    val task = ModelTask.Planner
    prompt.render(values) match {
      case Left(e) =>
        fallback(
          question,
          attempts.result(),
          s"planner prompt could not be rendered: $e",
          TaskCallCounters.PromptRender
        )
      case Right((system, user0)) =>
        var user = user0
        var result: Option[PlanDraft] = None
        var lastError = ""
        var lastKind = TaskCallCounters.OutputInvalid
        var n = 0
        while (result.isEmpty && n <= ctx.maxStructuredRetries) {
          n += 1
          model.invoke(
            ModelRequest(task, system, user, ctx.maxOutputTokens, PlannerFakeInput(question))
          ) match {
            case Left(f) =>
              attempts += ModelAttempt.callFailed(task.wire, n, f)
              lastError = s"model call failed: ${f.message.take(200)}"
              lastKind = TaskCallCounters.CallFailed
              n = Int.MaxValue - 1 // a failed call is not retried here (the client already retried)
            case Right(reply) =>
              PlanDraftSchema.validate(reply.text, b) match {
                case Right(d) =>
                  attempts += ModelAttempt.valid(task.wire, n, reply)
                  result = Some(d)
                case Left(err0) =>
                  val err = ModelAttempt.invalidError(reply, err0)
                  model.usage.recordInvalidOutput(task)
                  attempts += ModelAttempt.invalid(task.wire, n, reply, err0)
                  lastError = s"plan draft failed validation: ${err.take(200)}"
                  lastKind = TaskCallCounters.OutputInvalid
                  user =
                    user0 + s"\n\nYour previous output was rejected (${err.take(300)}). Return only a JSON object that matches query-plan-draft-v1."
              }
          }
        }
        result match {
          case Some(d) =>
            val source = if (model.provider == LlmProvider.Fake) PlanSource.Rule else PlanSource.Llm
            PlannerOutput(d, source, attempts.result(), None, None)
          case None => fallback(question, attempts.result(), lastError, lastKind)
        }
    }
  }

  private def fallback(
      question: String,
      attempts: List[ModelAttempt],
      reason: String,
      kind: String
  ): PlannerOutput = {
    model.usage.recordFallback(ModelTask.Planner, kind)
    val t0 = System.nanoTime()
    val d = rule.draft(question)
    // the rule planner's own output is validated like a model's
    val checked = PlanDraftSchema.validateJson(d.toJson, ctx.bounds).getOrElse(d)
    PlannerOutput(
      checked,
      PlanSource.Rule,
      attempts,
      Some(reason),
      Some((System.nanoTime() - t0) / 1000000L)
    )
  }
}

/** What the synthesis stage sees: the question, the executed steps, the facts kept in the bundle,
  * the built evidence and the runtime notes.
  */
final case class SynthesisInput(
    question: String,
    steps: List[PlannedStep],
    facts: List[Fact],
    evidence: BuiltEvidence,
    notes: List[String],
    uncertain: Map[ResearcherId, String],
    claims: AnswerClaimsSpec = AnswerClaimsSpec.V1
)

final case class SynthesisOutput(
    draft: AnswerDraft,
    usedModel: Boolean,
    modelId: String,
    attempts: List[ModelAttempt],
    fallbackReason: Option[String],
    budgetNotes: List[String],
    sanitizeNotes: List[String],
    millisInFallback: Option[Long]
) {
  def inputTokens: Option[Long] = sum(attempts.flatMap(_.inputTokens))
  def outputTokens: Option[Long] = sum(attempts.flatMap(_.outputTokens))
  private def sum(xs: List[Long]) = if (xs.isEmpty) None else Some(xs.sum)
}

/** Stage 8, AnswerSynthesis: the synthesis prompt of the prompt set (`answer-synthesis-v1` or
  * `-v2`) and its output schema (`claims`: `answer-claims-v1` or `-v2`). The prompt input is a
  * compact facts table plus the untrusted semantic excerpts, within `llm.contextTokenBudget` (4
  * characters per token; semantic excerpts are dropped first, then the lowest-ranked rows, and the
  * answer says so). Output that fails validation gets at most `llm.maxStructuredRetries` retries;
  * then, or when the model call fails, the deterministic template synthesis ([[FakeSynthesis]]) is
  * the fallback.
  */
final class AnswerSynthesis(
    model: LanguageModel,
    prompt: PromptTemplate,
    ctx: RuntimeContext,
    claims: AnswerClaimsSpec = AnswerClaimsSpec.V1
) {
  val CharsPerToken = 4

  def run(input: SynthesisInput): SynthesisOutput = {
    val budgetChars = ctx.contextTokenBudget.toLong * CharsPerToken
    val graph = input.evidence.graphRows.sortBy(_.priority)
    val semantic = input.evidence.semanticRows.sortBy(_._1.priority)
    def render(g: List[FactRow], s: List[(FactRow, Option[String])]) = prompt.render(
      Map(
        "FIELDS" -> EvidenceValues.Fields.mkString(", "),
        "QUESTION" -> Prompts.asData(input.question, QueryRequest.MaxQuestionChars),
        "NOTES" -> (if (input.notes.isEmpty) "none"
                    else input.notes.map(n => s"- ${Prompts.asData(n, 600)}").mkString("\n")),
        "FACTS" -> (if (g.isEmpty) "none" else g.map(_.render).mkString("\n")),
        "SEMANTIC" -> (if (s.isEmpty) "none"
                       else
                         s.map { case (r, ex) =>
                           s"<untrusted_excerpt evidenceId=\"${r.evidenceId.value}\" type=\"${r.evidenceType.wire}\">\n\"${ex.map(Prompts.asData(_, 400)).getOrElse("(text unavailable)")}\"\n</untrusted_excerpt>"
                         }.mkString("\n"))
      )
    )
    def size(r: Either[String, (String, String)]) =
      r.map { case (a, b) => (a.length + b.length).toLong }.getOrElse(0L)
    var g = graph
    var s = semantic
    var rendered = render(g, s)
    var droppedSemantic = 0
    var droppedRows = 0
    while (size(rendered) > budgetChars && s.nonEmpty) {
      s = s.init; droppedSemantic += 1; rendered = render(g, s)
    }
    while (size(rendered) > budgetChars && g.nonEmpty) {
      g = g.init; droppedRows += 1; rendered = render(g, s)
    }
    val budgetNotes =
      (if (droppedSemantic > 0)
         List(
           s"$droppedSemantic semantic excerpt(s) were left out of the model input to fit the context budget."
         )
       else Nil) ++
        (if (droppedRows > 0)
           List(
             s"$droppedRows lowest-ranked fact row(s) were left out of the model input to fit the context budget; they remain in the evidence bundle."
           )
         else Nil)
    val keptIds = (g.map(_.evidenceId) ++ s.map(_._1.evidenceId)).toSet
    val keptFacts = input.facts.filter(f => input.evidence.idOf.get(f).exists(keptIds.contains))
    val reduced = input.copy(facts = keptFacts, claims = claims)
    val attempts = List.newBuilder[ModelAttempt]
    val task = ModelTask.Synthesis

    rendered match {
      case Left(e) =>
        fallback(
          reduced,
          attempts.result(),
          s"synthesis prompt could not be rendered: $e",
          budgetNotes,
          TaskCallCounters.PromptRender
        )
      case Right((system, user0)) =>
        var user = user0
        var result: Option[AnswerDraft] = None
        var lastError = ""
        var lastKind = TaskCallCounters.OutputInvalid
        var n = 0
        while (result.isEmpty && n <= ctx.maxStructuredRetries) {
          n += 1
          model.invoke(
            ModelRequest(task, system, user, ctx.maxOutputTokens, SynthesisFakeInput(reduced))
          ) match {
            case Left(f) =>
              attempts += ModelAttempt.callFailed(task.wire, n, f)
              lastError = s"model call failed: ${f.message.take(200)}"
              lastKind = TaskCallCounters.CallFailed
              n = Int.MaxValue - 1
            case Right(reply) =>
              AnswerClaimsSchema.validate(reply.text, claims) match {
                case Right(d) =>
                  attempts += ModelAttempt.valid(task.wire, n, reply)
                  result = Some(d)
                case Left(err0) =>
                  val err = ModelAttempt.invalidError(reply, err0)
                  model.usage.recordInvalidOutput(task)
                  attempts += ModelAttempt.invalid(task.wire, n, reply, err0)
                  lastError = s"answer claims failed validation: ${err.take(200)}"
                  lastKind = TaskCallCounters.OutputInvalid
                  user =
                    user0 + s"\n\nYour previous output was rejected (${err.take(300)}). Return only a JSON object that matches ${claims.schemaId}."
              }
          }
        }
        result match {
          case Some(d) =>
            val (clean, notes) =
              AnswerSynthesis.sanitize(d, AnswerSynthesis.graphNames(reduced.evidence))
            SynthesisOutput(
              clean,
              usedModel = true,
              model.modelId,
              attempts.result(),
              None,
              budgetNotes,
              notes,
              None
            )
          case None => fallback(reduced, attempts.result(), lastError, budgetNotes, lastKind)
        }
    }
  }

  private def fallback(
      input: SynthesisInput,
      attempts: List[ModelAttempt],
      reason: String,
      budget: List[String],
      kind: String
  ): SynthesisOutput = {
    model.usage.recordFallback(ModelTask.Synthesis, kind)
    val t0 = System.nanoTime()
    val d = FakeSynthesis.render(input)
    val checked = AnswerClaimsSchema.validateJson(d.toJson, input.claims).getOrElse(d)
    SynthesisOutput(
      checked,
      usedModel = false,
      FakeModel.ModelId,
      attempts,
      Some(reason),
      budget,
      Nil,
      Some((System.nanoTime() - t0) / 1000000L)
    )
  }
}

object AnswerSynthesis {
  // `find` semantics over the whole text (a newline must not hide a digit or an identifier)
  private val Digit = "\\p{Nd}".r
  private val StableIdRe = "[a-z]+:v1:[0-9a-f]{8,}".r
  val DefaultSummary =
    "The answer below lists only claims checked against ScholarChain graph evidence."

  /** Label shown before a summary that was not replaced: the summary is never verified. */
  val SummaryLabel = "Overview (not verified; only the claims below are checked against evidence):"

  /** Label of the model-written missing-evidence notes. */
  val MissingEvidenceLabel = "Missing evidence (model-written, not verified):"

  /** Researcher display names of the bundle's graph items (never semantic context). */
  def graphNames(evidence: BuiltEvidence): Set[String] =
    evidence.items
      .filter(_.evidenceType != EvidenceType.SemanticContext)
      .flatMap(i => EvidenceValues.names(i) ++ EvidenceValues.pairs(i).get("askedName"))
      .map(normName)
      .toSet

  private def normName(s: String) =
    s.trim.replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT)

  /** The summary and missing-evidence notes may hold no digits, no identifiers and no researcher
    * name that is not in the bundle's graph items (facts belong in claims, where they are
    * verified). Offending text is replaced, never shown. The digit and identifier checks search the
    * whole text, across newlines.
    */
  def sanitize(d: AnswerDraft, names: Set[String]): (AnswerDraft, List[String]) = {
    def numeric(s: String) = Digit.findFirstIn(s).isDefined || StableIdRe.findFirstIn(s).isDefined
    def unknownName(s: String) =
      RulePlanner
        .nameRuns(s.replaceAll("[\\r\\n\\t]+", ". "))
        .exists(n => !names.contains(normName(n)))
    def bad(s: String) = numeric(s) || unknownName(s)
    val summaryBad = bad(d.summary)
    val missing = d.missingEvidence.filterNot(bad)
    val notes =
      (if (summaryBad)
         List(
           "The model summary contained numbers, identifiers or names not in the graph evidence and was replaced; facts appear only in verified claims."
         )
       else Nil) ++
        (if (missing.size < d.missingEvidence.size)
           List(
             s"${d.missingEvidence.size - missing.size} missing-evidence note(s) contained numbers, identifiers or names not in the graph evidence and were left out."
           )
         else Nil)
    (
      d.copy(summary = if (summaryBad) DefaultSummary else d.summary, missingEvidence = missing),
      notes
    )
  }
}

/** Deterministic claim templates over the facts table (the `fake` provider's synthesis and the
  * fallback). Every number in a claim is a stored value of a cited fact, formatted with
  * [[Fact.fmt]], and is listed in `numbers` with its evidence ID and field. The output stays within
  * the limits of the input's claims schema (`input.claims`): at most `maxClaims` claims (the first
  * ones, in template order), and a claim whose text is longer than `maxTextChars` is left out.
  */
object FakeSynthesis {
  import CollaborationMetric._

  private final class Builder(input: SynthesisInput) {
    val claims = mutable.ListBuffer.empty[DraftClaim]
    private val spec = input.claims
    def id(f: Fact): Option[String] = input.evidence.idOf.get(f).map(_.value)
    def add(text: String, cites: List[Fact], numbers: List[(Double, Fact, String)]): Unit = {
      val ids = cites.flatMap(id).distinct
      if (ids.nonEmpty && claims.size < spec.maxClaims && text.length <= spec.maxTextChars) {
        val nums = numbers.flatMap { case (v, f, field) =>
          id(f).map(DraftNumber(v, _, field))
        }.distinct
        val researchers = cites.flatMap(researchersOf)
        val unc = researchers.distinct
          .flatMap(r => input.uncertain.get(r).map(o => (r, o)))
          .headOption
          .map { case (_, o) =>
            s"Identity outcome is $o for a researcher in this claim; treat it as uncertain."
          }
        claims += DraftClaim(
          s"claim-${claims.size + 1}",
          text,
          ids,
          nums.take(spec.maxNumbersPerClaim),
          unc
        )
      }
    }
  }

  private def researchersOf(f: Fact): List[ResearcherId] = Fact.researchers(f)

  private def w(win: YearWindow) = Fact.windowText(win)
  private def v(x: Double) = Fact.fmt(x)

  def render(input: SynthesisInput): AnswerDraft = {
    val b = new Builder(input)
    val facts = input.facts
    val metrics = facts.collect { case m: MetricFact => m }
    val ops = input.steps.map(_.operation).toSet

    // identities
    facts.collect { case i: IdentityFact if i.resolved => i }.foreach { i =>
      val conf = i.confidence.map(c => s", confidence ${v(c)}").getOrElse("")
      b.add(
        s"${i.researcher.displayName} (${i.researcher.id.value}) is the researcher matched for this question (identity outcome ${i.outcome}$conf).",
        List(i),
        i.confidence.map(c => (c, i, "confidence")).toList
      )
    }

    // ranked rows (rankings, topic rankings, bridge rankings)
    val ranked = metrics.filter(_.rank.isDefined).sortBy(m => (m.metric.wire, m.rank.get))
    val ccdOf = metrics
      .filter(m => m.metric == CrossCommunityDegree && m.rank.isEmpty)
      .map(m => (m.researcher.id, m.windowLabel) -> m)
      .toMap
    val topic = input.steps.exists(_.topic.isDefined)
    ranked.foreach { m =>
      if (m.metric == CommunitiesConnected) {
        val ccd = ccdOf.get((m.researcher.id, m.windowLabel))
        b.add(
          s"Rank ${m.rank.get} by other communities connected in ${w(m.window)}: ${m.researcher.displayName} connects ${v(m.value)} other communities" +
            ccd.map(c => s" and has ${v(c.value)} coauthors in other communities.").getOrElse("."),
          m :: ccd.toList,
          List((m.rank.get.toDouble, m, "rank"), (m.value, m, "value")) ++ ccd.map(c =>
            (c.value, c, "value")
          )
        )
      } else {
        val among = if (topic) " among the topic-matched researchers" else ""
        b.add(
          s"Rank ${m.rank.get}$among by ${Fact.phrase(m.metric)} in ${w(m.window)}: ${m.researcher.displayName} with ${v(m.value)}.",
          List(m),
          List((m.rank.get.toDouble, m, "rank"), (m.value, m, "value"))
        )
      }
    }

    // per-researcher, per-window metrics (not ranked, not neighbour communities)
    val focal = metrics.filter(m =>
      m.rank.isEmpty && m.priority < 80 && !(m.metric == CrossCommunityDegree && ops.contains(
        QueryOperation.BridgeResearchers
      ))
    )
    focal
      .groupBy(m => (m.researcher, m.windowLabel))
      .toList
      .sortBy { case ((r, wl), ms) =>
        (ms.map(_.priority).min, ms.head.window.startYear, -ms.head.window.endYear, r.id.value)
      }
      .foreach { case ((r, _), ms) =>
        val by = ms.map(m => m.metric -> m).toMap
        val win = w(ms.head.window)
        val breadth = List(
          PublicationCount -> "publications (P)",
          DistinctCoauthors -> "distinct coauthors (C)",
          AverageCoauthorsPerPaper -> "average coauthors per paper (A)",
          MaxCoauthorsOnOnePaper -> "coauthors at most on one paper (M)"
        )
          .flatMap { case (k, label) => by.get(k).map(m => (m, s"${v(m.value)} $label")) }
        if (breadth.nonEmpty && !ops.contains(QueryOperation.CommunityAnalysis))
          b.add(
            s"In $win, ${r.displayName} has ${breadth.map(_._2).mkString(", ")}.",
            breadth.map(_._1),
            breadth.map { case (m, _) => (m.value, m, "value") }
          )
        (by.get(WeightedDegree), by.get(TotalCoauthorOccurrences)) match {
          case (Some(wd), Some(t)) if wd.value == t.value =>
            b.add(
              s"In $win, ${r.displayName}'s collaboration intensity is ${v(wd.value)}: weighted degree W equals total coauthor occurrences T in this data, so they are one finding.",
              List(wd, t),
              List((wd.value, wd, "value"))
            )
          case (wd, t) =>
            (wd.toList ++ t.toList).foreach(m =>
              b.add(
                s"In $win, ${r.displayName} has ${Fact.phrase(m.metric)} ${v(m.value)}.",
                List(m),
                List((m.value, m, "value"))
              )
            )
        }
        by.get(ComponentSize).foreach { m =>
          b.add(
            s"In $win, ${r.displayName} is in connected component ${m.groupId.map(_.value).getOrElse("?")} with ${v(m.value)} researchers.",
            List(m),
            List((m.value, m, "value"))
          )
        }
        by.get(CommunitySize).foreach { m =>
          b.add(
            s"In $win, ${r.displayName} is in community ${m.groupId.map(_.value).getOrElse("?")} with ${v(m.value)} members.",
            List(m),
            List((m.value, m, "value"))
          )
        }
        val bridge = List(
          DistinctCoauthors,
          CrossCommunityDegree,
          CommunitiesConnected,
          ParticipationCoefficient
        ).flatMap(by.get)
        if (ops.contains(QueryOperation.CommunityAnalysis) && bridge.nonEmpty) {
          val parts = bridge.map { m =>
            m.metric match {
              case DistinctCoauthors        => s"${v(m.value)} distinct coauthors (direct degree)"
              case CrossCommunityDegree     => s"${v(m.value)} coauthors in other communities"
              case CommunitiesConnected     => s"connections to ${v(m.value)} other communities"
              case ParticipationCoefficient => s"participation coefficient ${v(m.value)}"
              case _                        => ""
            }
          }
          b.add(
            s"In $win, ${r.displayName} has ${parts.mkString(", ")}.",
            bridge,
            bridge.map(m => (m.value, m, "value"))
          )
          b.add(
            s"A high distinct-coauthor count measures the breadth of ${r.displayName}'s direct collaboration, while bridge position is measured by the other communities connected; the two can differ.",
            bridge,
            Nil
          )
        }
      }

    // temporal changes
    if (ops.contains(QueryOperation.TemporalComparison)) {
      focal
        .groupBy(m => (m.researcher, m.metric))
        .toList
        .sortBy(x => (x._1._1.id.value, x._1._2.wire))
        .foreach { case ((r, metric), ms) =>
          val byWindow = ms.sortBy(m => (m.window.startYear, m.window.endYear))
          if (byWindow.size >= 2 && metric != TotalCoauthorOccurrences) {
            val (a, z) = (byWindow.head, byWindow.last)
            b.add(
              s"From ${w(a.window)} to ${w(z.window)}, ${r.displayName}'s ${Fact.phrase(metric)} changed from ${v(a.value)} to ${v(z.value)}.",
              List(a, z),
              List((a.value, a, "value"), (z.value, z, "value"))
            )
          }
        }
    }

    // works behind M
    facts.collect { case wf: WorkFact => wf }.foreach { wf =>
      val m = metrics.find(x =>
        x.metric == MaxCoauthorsOnOnePaper && wf.owner.contains(x.researcher) && x.rank.isEmpty
      )
      val title = wf.title.replace('"', '\'').trim
      b.add(
        s"\"$title\" (${wf.year}, ${wf.venueKey}) is a paper of ${wf.owner.map(_.displayName).getOrElse("this researcher")} with team size ${wf.teamSize}.",
        wf :: m.toList,
        List((wf.year.toDouble, wf, "year"), (wf.teamSize.toDouble, wf, "teamSize"))
      )
    }

    // paths
    facts.collect { case p: PathFact => p }.foreach { p =>
      b.add(
        s"In ${w(p.window)}, ${p.researchers.head.displayName} and ${p.researchers.last.displayName} are connected by a shortest coauthorship path of ${p.hops} hops: ${p.researchers.map(_.displayName).mkString(" -> ")}.",
        List(p),
        List((p.hops.toDouble, p, "hops"))
      )
    }
    facts.collect { case p: PathAbsentFact => p }.foreach { p =>
      if (p.complete)
        b.add(
          s"In ${w(p.window)}, no coauthorship path between ${p.from.displayName} and ${p.to.displayName} exists within depth ${p.maxDepth} (the bounded search completed).",
          List(p),
          List((p.maxDepth.toDouble, p, "maxDepth"))
        )
      else
        b.add(
          s"In ${w(p.window)}, the path search between ${p.from.displayName} and ${p.to.displayName} stopped at the time limit after depth ${p.depthSearched}, so whether a path exists is unknown.",
          List(p),
          List((p.depthSearched.toDouble, p, "depthSearched"))
        )
    }

    // edges (direct collaborations and path hops)
    facts.collect { case e: EdgeFact if e.priority < 80 => e }.foreach { e =>
      val years =
        if (e.firstYear == e.lastYear) s"in ${e.firstYear}"
        else s"from ${e.firstYear} to ${e.lastYear}"
      b.add(
        s"In ${w(e.window)}, ${e.a.displayName} and ${e.b.displayName} share ${e.jointPublicationCount} joint publications $years, with edge weight ${v(e.weight)}.",
        List(e),
        List(
          (e.jointPublicationCount.toDouble, e, "jointPublicationCount"),
          (e.firstYear.toDouble, e, "firstYear"),
          (e.lastYear.toDouble, e, "lastYear"),
          (e.weight, e, "weight")
        )
      )
    }
    facts.collect { case e: EdgeAbsentFact => e }.foreach { e =>
      b.add(
        s"In ${w(e.window)}, ${e.a.displayName} and ${e.b.displayName} have no direct coauthor edge.",
        List(e),
        Nil
      )
    }

    // neighbour communities
    val neighbourEdges = facts.collect { case e: EdgeFact if e.priority >= 80 => e }
    metrics.filter(m => m.priority >= 80 && m.metric == CommunitySize).foreach { m =>
      neighbourEdges.find(e => e.a.id == m.researcher.id || e.b.id == m.researcher.id).foreach {
        e =>
          val focalRef = if (e.a.id == m.researcher.id) e.b else e.a
          b.add(
            s"In ${w(m.window)}, ${focalRef.displayName} and ${m.researcher.displayName} are coauthors, and ${m.researcher.displayName} is in another community, ${m.groupId.map(_.value).getOrElse("?")}, with ${v(m.value)} members.",
            List(e, m),
            List((m.value, m, "value"))
          )
      }
    }

    // semantic context (descriptive only)
    val sem = facts.collect { case s: SemanticFact => s }
    if (sem.nonEmpty)
      b.add(
        "Researcher profiles similar to the question text were retrieved as descriptive context only; text similarity is not graph evidence.",
        sem,
        Nil
      )

    val summary = input.steps.headOption.map(_.operation) match {
      case Some(QueryOperation.ResearcherRanking) =>
        "Researchers ranked by the requested stored metric."
      case Some(QueryOperation.BridgeResearchers) =>
        "Researchers ranked by the number of other communities their coauthors belong to."
      case Some(QueryOperation.ShortestPath) =>
        "Shortest coauthorship path between the two researchers, with the evidence of every hop."
      case Some(QueryOperation.CoauthorNeighborhood) =>
        "Direct coauthor edges with their joint publications and weights."
      case Some(QueryOperation.MaxTeamSize) => "Largest team size and the papers that reach it."
      case Some(QueryOperation.ConnectedComponent) => "Connected component membership and size."
      case Some(QueryOperation.CommunityAnalysis)  =>
        "Community membership, direct degree and bridge position."
      case Some(QueryOperation.TemporalComparison) =>
        "Stored metrics compared across the time windows."
      case _ => "Stored collaboration metrics for the requested researchers."
    }
    AnswerDraft(summary, b.claims.toList, Nil)
  }
}
