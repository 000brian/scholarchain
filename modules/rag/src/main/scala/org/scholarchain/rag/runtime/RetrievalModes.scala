package org.scholarchain.rag.runtime

import org.scholarchain.core._
import org.scholarchain.rag.retrieval.SemanticRetriever
import org.scholarchain.storage.ports.GraphQuery
import org.scholarchain.storage.runtime.RuntimeStores

/** One entry of a mode's ranked list (rank 1 is best). `score` is the mode's own number: the stored
  * metric value or edge weight (graph-only), the similarity (vector-only), the RRF score (fused);
  * absent when the mode has none.
  */
final case class RankedEntry(researcherId: String, rank: Int, score: Option[Double]) {
  def toJson: ujson.Obj = ujson.Obj(
    "researcherId" -> researcherId,
    "rank" -> rank,
    "score" -> score.map(ujson.Num(_)).getOrElse(ujson.Null)
  )
}

/** What graph-only returned for one question: its ranked list and the abstention codes of the plan
  * and its graph stages (fixed codes, [[AbstentionCodes]]).
  */
final case class GraphOnlyResult(
    entries: List[RankedEntry],
    abstentionCodes: List[String],
    operations: List[String],
    windowLabel: String
)

/** What vector-only returned for one question. */
final case class VectorOnlyResult(
    entries: List[RankedEntry],
    hits: Int,
    windowLabel: String,
    droppedIncompatibleModel: Int,
    droppedMalformedMetadata: Int
)

/** The three retrieval modes of `rag-retrieval-eval` (Phase 15, decision 7.4). No LLM call.
  *
  *   - graph-only: the deterministic rule planner, the researcher resolver and the workflow's graph
  *     stages (CollaborationMetrics, PathFinder, CommunityAnalyst) on Neptune and DynamoDB, exactly
  *     as [[QueryWorkflow]] runs them, without synthesis and without semantic retrieval (a topic
  *     filter therefore abstains with `topic-candidates-failed`). The ranked list: ranked metric
  *     facts by rank, then the researchers of the other facts in stage order, without the step's
  *     own input researchers (the answer to "collaborators of X" is not X), one entry each.
  *   - vector-only: the question embedded with the configured query model and the top-K
  *     researcher-profile hits in the plan's window (the rule planner's window, else the default);
  *     the researcher ID is the hit's metadata `researcherId`; several hits of one researcher
  *     collapse to its best rank and score.
  *   - fused ([[RetrievalModes.fuse]]): reciprocal-rank fusion of the two lists, k = 60, version
  *     `fusion-exp-v1`: experiment only; the production workflow has no fused ranking.
  *
  * Thread-safe when the stores and retriever are (one instance serves one evaluation; each call
  * builds its own stage objects).
  */
final class RetrievalModes(
    stores: RuntimeStores,
    ctx: RuntimeContext,
    retriever: Option[SemanticRetriever],
    pathIndex: String
) {
  private val rule = new RulePlanner(ctx.windows, ctx.bounds)

  private def planned(question: String): (String, PlanDraft, String) = {
    val q = QueryRequest.canonicalQuestion(question).take(QueryRequest.MaxQuestionChars)
    val draft = rule.draft(q)
    (q, draft, draft.windowLabel.getOrElse(ctx.runtime.defaultWindowLabel))
  }

  def graphOnly(question: String): GraphOnlyResult = {
    val (q, draft, window) = planned(question)
    val res = new ResearcherResolver(stores, ctx).resolve(draft.researcherNames, draft.orcids)
    val a = PlanAssembly.assemble(ctx, q, QueryRequest(q), draft, res)
    val noSemantic = new SemanticContextStage(None, stores, ctx)
    val pf = new PathFinder(stores, ctx, pathIndex)
    val out = new CollaborationMetrics(stores, ctx, noSemantic).run(a.executable) ++
      pf.run(a.executable) ++
      new CommunityAnalyst(stores, ctx, pf).run(a.executable)
    val subjects = a.executable.flatMap(_.researchers.map(_.id.value)).toSet
    val ranked = out.facts
      .collect { case m: MetricFact if m.rank.isDefined => (m.rank.get, m) }
      .sortBy(_._1)
      .map { case (_, m) => (m.researcher.id.value, Some(m.value)) }
    val others = out.facts.flatMap {
      case m: MetricFact if m.rank.isDefined => Nil
      case e: EdgeFact => List((e.a.id.value, Some(e.weight)), (e.b.id.value, Some(e.weight)))
      case f           => Fact.researchers(f).map(id => (id.value, Option.empty[Double]))
    }
    val list = (ranked ++ others)
      .filterNot { case (id, _) => subjects.contains(id) || !ResearcherId.isWellFormed(id) }
      .distinctBy(_._1)
      .zipWithIndex
      .map { case ((id, s), i) => RankedEntry(id, i + 1, s) }
    val codes =
      (a.abstainAll ++ a.abstentions ++ out.abstentions).map(_.code).distinct
    GraphOnlyResult(list, codes, a.executable.map(_.operation.wire).distinct, window)
  }

  /** Left: the retrieval failed (fixed text, never a provider or store message). */
  def vectorOnly(question: String, topK: Int): Either[String, VectorOnlyResult] = {
    val (q, _, window) = planned(question)
    for {
      r <- retriever.toRight("semantic retrieval is not configured")
      w <- ctx.yearWindow(window).toRight(s"window $window is not configured")
      res <- r.retrieve(
        SemanticDocumentType.ResearcherProfile,
        q.take(SemanticRetriever.MaxQueryChars),
        topK,
        Some(SemanticRetriever.windowFilter(w.startYear, w.endYear))
      )
    } yield {
      val hits = res.context.entries
        .filter(_.documentType == SemanticDocumentType.ResearcherProfile)
        .zipWithIndex
        .flatMap { case (e, i) =>
          e.subjectIds.headOption.filter(ResearcherId.isWellFormed).map(id => (id, e.score, i))
        }
      // best rank first: higher similarity, then the store's own order
      val collapsed = hits
        .sortBy { case (_, s, i) => (-s, i) }
        .distinctBy(_._1)
        .zipWithIndex
        .map { case ((id, s, _), i) => RankedEntry(id, i + 1, Some(s)) }
      VectorOnlyResult(
        collapsed,
        res.context.entries.size,
        window,
        res.droppedIncompatibleModel,
        res.droppedMalformedMetadata.size
      )
    }
  }

  /** Whether the loaded graph holds `researcherId` (Neptune `researcher_by_id`); Left: the read
    * failed. Used for the per-question drift count (labels missing from the graph stay in the
    * denominator).
    */
  def inGraph(researcherId: String): Either[String, Boolean] =
    if (!ResearcherId.isWellFormed(researcherId)) Right(false)
    else
      stores.graph
        .run(GraphQuery.ResearcherById(ResearcherId(researcherId)))
        .map(_.rows.nonEmpty)
        .left
        .map(_ => "graph read failed")
}

object RetrievalModes {
  val FusionVersion = "fusion-exp-v1"
  val FusionK = 60
  val FusionLabel = "experiment only; the production workflow has no fused ranking"

  /** Reciprocal-rank fusion over the union of `lists`: score(id) = sum over the lists holding id of
    * 1 / (k + rank); an ID absent from a list contributes nothing from it. Sorted by score
    * descending, then researcher ID by code point.
    */
  def fuse(lists: List[List[RankedEntry]], k: Int = FusionK): List[RankedEntry] = {
    require(k > 0, "k must be > 0")
    val scores = lists
      .flatMap(_.map(e => e.researcherId -> 1.0 / (k + e.rank)))
      .groupMapReduce(_._1)(_._2)(_ + _)
    scores.toList
      .sortWith { case ((a, sa), (b, sb)) =>
        if (sa != sb) sa > sb else CodePointOrder.ordering.lt(a, b)
      }
      .zipWithIndex
      .map { case ((id, s), i) => RankedEntry(id, i + 1, Some(s)) }
  }
}
