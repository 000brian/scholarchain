package org.scholarchain.rag.runtime

import scala.collection.immutable.ListMap
import scala.util.control.NonFatal

import org.scholarchain.core._
import org.scholarchain.rag.retrieval.SemanticRetriever
import org.scholarchain.storage.runtime.{AuditWrite, BundleWrite, RuntimeStores}

/** Everything one query produced. The response and audit are contract-valid core records. */
final case class WorkflowResult(
    queryRunId: RunId,
    request: QueryRequest,
    draft: PlanDraft,
    planSource: PlanSource,
    plannedSteps: List[PlannedStep],
    resolver: ResolverOutput,
    evidence: BuiltEvidence,
    verified: List[VerifiedClaim],
    synthesis: SynthesisOutput,
    planner: PlannerOutput,
    response: AnswerResponse,
    audit: QueryAudit,
    bundleWrite: Option[Either[String, BundleWrite]],
    auditWrite: Either[String, AuditWrite],
    stageMillis: ListMap[String, Long],
    notes: List[String],
    abstentions: List[String]
) {
  def attempts: List[ModelAttempt] = planner.attempts ++ synthesis.attempts
}

/** Stage 10, Audit: builds the [[QueryAudit]], appends it with storage's `QueryAuditWriter`, and
  * stores the bundle with `EvidenceStore` (EB3, put-if-absent; each item also as EB1).
  */
final class AuditStage(stores: RuntimeStores) {
  def persist(
      bundle: Option[EvidenceBundle],
      audit: QueryAudit
  ): (Option[Either[String, BundleWrite]], Either[String, AuditWrite]) =
    (bundle.map(stores.evidence.putBundle), stores.audits.append(audit))
}

/** The runtime collaboration-analysis workflow: QueryPlanner, ResearcherResolver,
  * CollaborationMetrics, PathFinder, CommunityAnalyst, SemanticContext, EvidenceBuilder,
  * AnswerSynthesis, ClaimVerification and Audit, in that order. Stores are reached only through
  * storage's [[RuntimeStores]].
  *
  * The query run ID is `RunId.of("rag.query", CodeVersion, sha256(canonical question and
  * constraints), config fingerprint)`, so the same question and configuration give the same run ID.
  */
final class QueryWorkflow(
    stores: RuntimeStores,
    ctx: RuntimeContext,
    model: LanguageModel,
    prompts: PromptSet,
    retriever: Option[SemanticRetriever],
    pathIndex: String,
    clock: () => Long = () => System.nanoTime()
) {
  import QueryWorkflow._

  private val rule = new RulePlanner(ctx.windows, ctx.bounds)
  private val planner = new QueryPlanner(model, prompts.planner, rule, ctx)
  private val resolver = new ResearcherResolver(stores, ctx)
  private val evidenceBuilder = new EvidenceBuilder(ctx)
  private val synthesis = new AnswerSynthesis(model, prompts.synthesis, ctx)
  private val auditStage = new AuditStage(stores)

  def queryRunId(r: QueryRequest): Either[String, RunId] =
    RunId.of(JobName, CodeVersion, QueryRequest.fingerprint(r), ctx.configFingerprint)

  def run(request: QueryRequest): Either[String, WorkflowResult] =
    try runChecked(request)
    catch {
      case NonFatal(e) =>
        Left(
          s"workflow failed: ${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("").take(300)}"
        )
    }

  private def runChecked(request: QueryRequest): Either[String, WorkflowResult] = {
    val question = QueryRequest.canonicalQuestion(request.question)
    val t0 = clock()
    val stages = ListMap.newBuilder[String, Long]
    def timed[A](name: String)(f: => A): A = {
      val s = clock(); val a = f; stages += name -> math.max(0L, (clock() - s) / 1000000L); a
    }
    for {
      _ <- Either.cond(question.nonEmpty, (), "question is blank")
      _ <- Either.cond(
        question.length <= QueryRequest.MaxQuestionChars,
        (),
        s"question exceeds ${QueryRequest.MaxQuestionChars} characters"
      )
      _ <- request.windowLabel.fold[Either[String, Unit]](Right(()))(w =>
        Either.cond(ctx.windowLabels.contains(w), (), s"window '$w' is not a configured window")
      )
      runId <- queryRunId(request.copy(question = question))
      plannerOut = timed("planner")(planner.plan(question))
      _ = plannerOut.millisInFallback.foreach(ms => stages += "plannerFallback" -> ms)
      draft = plannerOut.draft
      resolution = timed("resolver")(resolver.resolve(draft.researcherNames, draft.orcids))
      assembled = assemble(question, request, draft, resolution)
      semanticStage = new SemanticContextStage(retriever, stores, ctx)
      metricsOut = timed("metrics")(
        new CollaborationMetrics(stores, ctx, semanticStage).run(assembled.executable)
      )
      pathFinder = new PathFinder(stores, ctx, pathIndex)
      pathsOut = timed("paths")(pathFinder.run(assembled.executable))
      communityOut = timed("community")(
        new CommunityAnalyst(stores, ctx, pathFinder).run(assembled.executable)
      )
      semanticOut = timed("semantic")(semanticStage.run(assembled.executable))
      retrieved = metricsOut ++ pathsOut ++ communityOut ++ semanticOut
      facts = resolution.facts ++ retrieved.facts
      built <- timed("evidence")(evidenceBuilder.build(runId, facts))
      // every researcher in the bundle's facts, however it entered (ranking, bridge ranking,
      // path hop, neighbour, edge), not only name-resolved ones
      bundled = facts.filter(built.idOf.contains)
      identity = timed("identity")(new IdentityCheck(stores).run(bundled, resolution))
      uncertain = identity.uncertain
      peopleOf = bundled
        .flatMap(f => built.idOf.get(f).map(_ -> Fact.researchers(f)))
        .groupMapReduce(_._1)(_._2)(_ ++ _)
      baseNotes = assembled.notes ++ resolution.notes ++
        IdentityCheck.notes(identity, resolution.uncertain.keySet) ++ retrieved.notes ++
        (if (built.capped)
           List(
             s"The evidence bundle was capped at ${ctx.runtime.maxBundleItems} items; ${built.droppedFacts} lower-ranked item(s) were left out."
           )
         else Nil)
      abstentions = assembled.abstentions ++ retrieved.abstentions
      synthOut = timed("synthesis")(
        synthesis.run(
          SynthesisInput(
            question,
            assembled.executable,
            facts.filter(built.idOf.contains),
            built,
            baseNotes,
            uncertain
          )
        )
      )
      _ = synthOut.millisInFallback.foreach(ms => stages += "synthesisFallback" -> ms)
      verified = timed("verification")(ClaimVerification.verify(synthOut.draft, built.bundle))
      result <- timed("audit")(
        finish(
          runId,
          question,
          request,
          plannerOut,
          resolution,
          assembled,
          built,
          synthOut,
          verified,
          baseNotes,
          abstentions,
          identity,
          peopleOf,
          t0,
          stages
        )
      )
    } yield result
  }

  private def finish(
      runId: RunId,
      question: String,
      request: QueryRequest,
      plannerOut: PlannerOutput,
      resolution: ResolverOutput,
      assembled: Assembled,
      built: BuiltEvidence,
      synthOut: SynthesisOutput,
      verified: List[VerifiedClaim],
      baseNotes: List[String],
      abstentions: List[String],
      identity: IdentityOutcomes,
      peopleOf: Map[EvidenceId, List[ResearcherId]],
      t0: Long,
      stages: collection.mutable.Builder[(String, Long), ListMap[String, Long]]
  ): Either[String, WorkflowResult] = {
    val uncertain = identity.uncertain
    val itemsById = built.items.map(i => i.evidenceId -> i).toMap
    val claims = verified.map { v =>
      val window = v.validEvidence.flatMap(itemsById.get).flatMap(_.window).headOption
      // every uncertain researcher of every cited fact, plus any researcher ID the text names
      val people = (v.validEvidence.flatMap(id => peopleOf.getOrElse(id, Nil)) ++
        v.validEvidence
          .flatMap(itemsById.get)
          .flatMap(EvidenceValues.ids)
          .map(ResearcherId(_))
          .filter(id => v.claim.text.contains(id.value))).distinct
      val flagged = people.flatMap(id => uncertain.get(id).map(id -> _))
      val identityNote =
        if (flagged.isEmpty) None
        else
          Some(
            s"Identity outcome is not accepted for ${flagged
                .take(5)
                .map { case (id, o) =>
                  s"${identity.names.getOrElse(id, id.value)} (${id.value}): $o"
                }
                .mkString("; ")}${
                if (flagged.size > 5) s"; and ${flagged.size - 5} more" else ""
              }; treat this claim as uncertain."
          )
      val partialNote =
        if (v.status == VerificationStatus.PartiallySupported)
          Some(
            s"Partially supported: ${v.reasons.headOption.getOrElse("some parts are not covered by cited evidence")}."
          )
        else None
      val uncertainty =
        List(v.claim.uncertainty, partialNote, identityNote).flatten.distinct match {
          case Nil => None
          case xs  => Some(xs.mkString(" ").take(600))
        }
      val text =
        if (v.kept) v.claim.text
        else
          s"Removed (${v.status.wire}): ${v.reasons.headOption.getOrElse("not supported by the evidence bundle")}"
      // an unsupported claim cites nothing; every other status keeps its valid citations (a
      // conflicting claim always has some: conflicts are found only against cited graph items)
      val ids2 = if (v.status == VerificationStatus.Unsupported) Nil else v.validEvidence
      AnswerClaim(
        2,
        v.claim.claimId,
        text,
        ids2,
        v.status,
        window,
        if (v.kept) uncertainty else None,
        Provenance(
          1,
          SourceType.Runtime,
          EvidenceBuilder.DatasetId,
          ctx.graphRunId.value,
          (if (ids2.isEmpty) List(ctx.graphRunId.value) else ids2.map(_.value)),
          runId,
          "rag.answer",
          prompts.synthesis.promptId,
          Some(ContentHash.of(text))
        )
      )
    }
    val kept = verified.filter(_.kept)
    val removed = verified.filterNot(_.kept)
    val notes = (baseNotes ++ synthOut.budgetNotes ++ synthOut.sanitizeNotes ++
      synthOut.fallbackReason
        .map(r =>
          s"The synthesis model output could not be used ($r); claims were written by the deterministic template fallback."
        )
        .toList ++
      plannerOut.fallbackReason
        .map(r =>
          s"The planner model output could not be used ($r); the deterministic rule planner was used."
        )
        .toList).distinct
    // an answer needs at least one kept claim beyond identity matching (match_decision items)
    val substantive = kept.filter(
      _.validEvidence.flatMap(itemsById.get).exists(_.evidenceType != EvidenceType.MatchDecision)
    )
    val abstained = substantive.isEmpty || assembled.abstainAll.nonEmpty
    val abstentionReason =
      if (!abstained) None
      else
        Some {
          val reasons = (assembled.abstainAll ++ abstentions ++
            (if (substantive.isEmpty && verified.exists(!_.kept))
               List("no claim could be verified against the evidence")
             else Nil)).distinct
          (if (reasons.isEmpty) List("no graph evidence answered the question") else reasons)
            .mkString("; ")
            .take(1000)
        }
    val answerText = {
      val sb = new StringBuilder
      if (abstained) sb.append(s"No answer: ${abstentionReason.get}.\n")
      else if (synthOut.draft.summary == AnswerSynthesis.DefaultSummary)
        sb.append(synthOut.draft.summary).append("\n")
      else
        // the summary is never verified: it is labelled, and it holds no digit, identifier or
        // name outside the graph evidence (AnswerSynthesis.sanitize)
        sb.append(s"${AnswerSynthesis.SummaryLabel} ${synthOut.draft.summary}").append("\n")
      if (!abstained && kept.nonEmpty) {
        sb.append("\nClaims:\n")
        claims.filter(c => kept.exists(_.claim.claimId == c.claimId)).foreach { c =>
          sb.append(s"- [${c.claimId}] ${c.claimText}")
          c.uncertainty.foreach(u => sb.append(s" (Uncertainty: $u)"))
          sb.append("\n")
        }
      }
      if (!abstained && abstentions.nonEmpty)
        sb.append("\nNot answered:\n")
          .append(abstentions.distinct.map(a => s"- $a").mkString("\n"))
          .append("\n")
      if (removed.nonEmpty) {
        sb.append("\nRemoved claims:\n")
        removed.foreach(r =>
          sb.append(
            s"- ${r.claim.claimId}: ${r.status.wire}: ${r.reasons.headOption.getOrElse("unsupported")}\n"
          )
        )
      }
      if (synthOut.draft.missingEvidence.nonEmpty)
        sb.append(s"\n${AnswerSynthesis.MissingEvidenceLabel}\n")
          .append(synthOut.draft.missingEvidence.map(m => s"- $m").mkString("\n"))
          .append("\n")
      if (notes.nonEmpty)
        sb.append("\nNotes:\n").append(notes.map(n => s"- $n").mkString("\n")).append("\n")
      sb.toString.trim
    }
    val bundle = built.bundle
    val plan = assembled.plan(runId, question, plannerOut, ctx, prompts)
    val identityNotes = resolution.notes
    val modelId = Some(synthOut.modelId)
    val response = AnswerResponse(
      2,
      question,
      answerText,
      modelId,
      plan,
      bundle,
      claims,
      identityNotes,
      abstained,
      abstentionReason,
      Provenance(
        1,
        SourceType.Runtime,
        EvidenceBuilder.DatasetId,
        ctx.graphRunId.value,
        List(bundle.map(_.bundleId.value).getOrElse(ctx.graphRunId.value)),
        runId,
        "rag.answer",
        prompts.synthesis.promptId,
        Some(ContentHash.of(answerText))
      )
    )
    val auditStart = clock()
    val stageSoFar = stages.result()
    val counts = VerificationStatus.values.map(s => s.wire -> claims.count(_.status == s)).toMap
    val policies = built.items
      .filter(_.evidenceType == EvidenceType.Metric)
      .map(_.provenance.policyVersion)
      .distinct
      .sorted
    val tokensIn = sumOpt(
      plannerOut.attempts.flatMap(_.inputTokens) ++ synthOut.attempts.flatMap(_.inputTokens)
    )
    val tokensOut = sumOpt(
      plannerOut.attempts.flatMap(_.outputTokens) ++ synthOut.attempts.flatMap(_.outputTokens)
    )
    val audit = QueryAudit(
      1,
      runId,
      ContentHash.of(question),
      plan,
      plannerOut.source,
      resolution.resolved.map(_.candidate.ref.id).distinct,
      resolution.unresolved.map(_.asked).distinct,
      built.items.map(_.evidenceId),
      bundle.map(_.bundleId),
      ctx.graphRunId,
      policies,
      prompts.versions,
      modelId,
      tokensIn,
      tokensOut,
      math.max(0L, (clock() - t0) / 1000000L),
      stageSoFar.toMap,
      counts,
      ContentHash.of(answerText),
      abstained,
      Provenance(
        1,
        SourceType.Runtime,
        EvidenceBuilder.DatasetId,
        ctx.graphRunId.value,
        List(ContentHash.of(question)),
        runId,
        JobName,
        "query-audit/1",
        None
      )
    )
    for {
      _ <- Contract.validate(response) match {
        case Nil => Right(());
        case e   => Left(s"answer response failed its contract: ${e.take(5).mkString("; ")}")
      }
      _ <- Contract.validate(audit) match {
        case Nil => Right(());
        case e   => Left(s"query audit failed its contract: ${e.take(5).mkString("; ")}")
      }
    } yield {
      val (bw, aw) = auditStage.persist(bundle, audit)
      val _ = auditStart
      WorkflowResult(
        runId,
        request,
        plannerOut.draft,
        plannerOut.source,
        assembled.executable,
        resolution,
        built,
        verified,
        synthOut,
        plannerOut,
        response,
        audit,
        bw,
        aw,
        stageSoFar,
        notes,
        abstentions
      )
    }
  }

  private def sumOpt(xs: List[Long]): Option[Long] = if (xs.isEmpty) None else Some(xs.sum)

  /** The executable steps, the abstentions and the notes for a validated draft and its resolution.
    */
  private[runtime] def assemble(
      question: String,
      request: QueryRequest,
      d: PlanDraft,
      res: ResolverOutput
  ): Assembled = {
    val notes = List.newBuilder[String]
    val abstainAll = List.newBuilder[String]
    val abstain = List.newBuilder[String]
    val windowLabel = request.windowLabel.orElse(d.windowLabel).getOrElse {
      if (
        d.comparisonWindowLabels.isEmpty && !d.operations.forall(
          _ == QueryOperation.ResearcherLookup
        )
      )
        notes += s"No window was given; the default window ${ctx.runtime.defaultWindowLabel} (${ctx.windowOf(ctx.runtime.defaultWindowLabel).map(w => s"${w.startYear}-${w.endYear}").getOrElse("")}) was used."
      ctx.runtime.defaultWindowLabel
    }
    d.unsupportedConstraints.foreach { c =>
      c.kind match {
        case "venue" =>
          abstainAll += s"venue filters are not supported yet (no venue metrics exist): ${c.text}"
        case "institution" =>
          abstainAll += s"institution filters are not supported yet (no institution data exists): ${c.text}"
        case "window" =>
          abstainAll += s"the requested years are not a configured window: ${c.text} (windows: ${ctx.windows.map(w => s"${w.startYear}-${w.endYear}").mkString(", ")})"
        case _ => notes += s"Not supported: ${c.text}"
      }
    }
    val resolvedByName = res.resolved.map(r => r.asked -> r.candidate.ref).toMap
    val ordered = (d.researcherNames ++ d.orcids).flatMap(resolvedByName.get).distinct
    val anyUnresolved = res.unresolved.nonEmpty
    val limit = d.limit.getOrElse(math.min(DefaultLimit, ctx.runtime.rankingCacheTopK))
    val depth = d.maxPathDepth.getOrElse(ctx.runtime.maxPathDepth)
    val steps = d.operations.map { op =>
      PlannedStep(
        op,
        ordered,
        if (op == QueryOperation.TemporalComparison)
          request.windowLabel.getOrElse(ctx.fullWindowLabel)
        else windowLabel,
        if (op == QueryOperation.TemporalComparison) d.comparisonWindowLabels else Nil,
        d.rankingMetric,
        limit,
        depth,
        d.topic
      )
    }
    val executable = steps.filter { s =>
      val needs = s.operation match {
        case QueryOperation.ShortestPath                          => 2
        case op if QueryOperation.mayOmitResearchers.contains(op) => 0
        case _                                                    => 1
      }
      val askedForPeople = d.researcherNames.nonEmpty || d.orcids.nonEmpty
      val ok =
        if (s.operation == QueryOperation.ShortestPath) s.researchers.size == 2 && !anyUnresolved
        else if (needs == 0)
          !(askedForPeople && anyUnresolved && s.operation == QueryOperation.SemanticContext && s.topic.isEmpty)
        else s.researchers.size >= needs
      if (!ok) {
        val why =
          if (anyUnresolved)
            s"${s.operation.wire} was not run because a researcher name could not be resolved to exactly one researcher"
          else if (s.operation == QueryOperation.ShortestPath)
            "a shortest path needs exactly two researcher names"
          else s"${s.operation.wire} needs a researcher name"
        abstain += why
      }
      ok
    }
    Assembled(
      steps,
      if (abstainAll.result().nonEmpty) Nil else executable,
      notes.result(),
      abstain.result(),
      abstainAll.result(),
      res
    )
  }
}

/** The assembled plan of one question. */
private[runtime] final case class Assembled(
    planned: List[PlannedStep],
    executable: List[PlannedStep],
    notes: List[String],
    abstentions: List[String],
    abstainAll: List[String],
    resolution: ResolverOutput
) {

  /** The core QueryPlan. A step whose researchers could not be resolved is recorded as a
    * researcher_lookup over the candidates that were looked up (parameter `plannedOperation`), or,
    * when there is no candidate at all, as a not-executed placeholder step; no researcher is ever
    * chosen for an unresolved name.
    */
  def plan(
      runId: RunId,
      question: String,
      p: PlannerOutput,
      ctx: RuntimeContext,
      prompts: PromptSet
  ): QueryPlan = {
    val candidates = resolution.resolutions.flatMap {
      case Resolution.Ambiguous(_, cs, _, _) => cs.map(_.ref.id)
      case _                                 => Nil
    }
    val steps = planned.map { s =>
      val window = ctx.yearWindow(s.windowLabel)
      val params = ListMap.empty[String, String] ++
        s.metric.map("metric" -> _.wire) ++
        (if (
           Set[QueryOperation](
             QueryOperation.ResearcherRanking,
             QueryOperation.BridgeResearchers,
             QueryOperation.CoauthorNeighborhood
           ).contains(s.operation)
         ) List("limit" -> s.limit.toString)
         else Nil) ++
        (if (s.operation == QueryOperation.ShortestPath) List("maxDepth" -> s.maxDepth.toString)
         else Nil) ++
        s.topic.map("topic" -> _) ++
        (if (s.comparisonWindows.nonEmpty) List("windows" -> s.comparisonWindows.mkString(","))
         else Nil) ++
        List("windowLabel" -> s.windowLabel)
      val ids = s.researchers.map(_.id)
      if (executable.contains(s)) QueryStep(s.operation, ids, window, params.toMap)
      else {
        val lookup = (ids ++ candidates).distinct.take(10)
        if (lookup.nonEmpty)
          QueryStep(
            QueryOperation.ResearcherLookup,
            lookup,
            window,
            (params + ("plannedOperation" -> s.operation.wire) + ("status" -> "not-executed")).toMap
          )
        else
          QueryStep(
            QueryOperation.SemanticContext,
            Nil,
            window,
            (params + ("plannedOperation" -> s.operation.wire) + ("status" -> "not-executed")).toMap
          )
      }
    }
    val policy =
      if (p.source == PlanSource.Llm) prompts.planner.version else RulePlanner.PolicyVersion
    QueryPlan(
      1,
      question,
      ctx.graphRunId,
      steps,
      Provenance(
        1,
        SourceType.Runtime,
        EvidenceBuilder.DatasetId,
        ctx.graphRunId.value,
        List(ctx.graphRunId.value),
        runId,
        QueryWorkflow.JobName,
        policy,
        Some(ContentHash.of(question))
      )
    )
  }
}

object QueryWorkflow {
  val JobName = "rag.query"
  val CodeVersion = "rag-runtime/1"
  val DefaultLimit = 10
  val Label = "local stores, fake embeddings"
}
