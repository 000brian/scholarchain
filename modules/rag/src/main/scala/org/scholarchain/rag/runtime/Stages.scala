package org.scholarchain.rag.runtime

import scala.collection.mutable

import org.scholarchain.core._
import org.scholarchain.rag.retrieval.{SemanticContextEntry, SemanticRetriever}
import org.scholarchain.storage.dynamo.RankingOrder
import org.scholarchain.storage.dynamo.RankedEntry
import org.scholarchain.storage.ports.{
  GraphQuery,
  GraphReadPort,
  GraphReadSettings,
  GraphResult,
  MetaValue,
  MetadataFilter,
  VectorHit,
  VectorIndexPort,
  VectorRecord
}
import org.scholarchain.storage.runtime.{MetricCacheEntry, RuntimeStores}
import org.scholarchain.storage.vectors.{VectorKeys, VectorMetadataCodec}

/** One executable plan step: the operation with resolved researchers and allowlisted parameters. */
final case class PlannedStep(
    operation: QueryOperation,
    researchers: List[ResearcherRef],
    windowLabel: String,
    comparisonWindows: List[String],
    metric: Option[CollaborationMetric],
    limit: Int,
    maxDepth: Int,
    topic: Option[String]
)

/** Facts, notes and abstentions of one retrieval stage. `abstentions` are reasons why a step could
  * not be answered (their texts are stated in the answer; their fixed codes go into the audit,
  * Phase 15 G-28).
  */
final case class StageOutput(
    facts: List[Fact],
    notes: List[String],
    abstentions: List[Abstention]
) {
  def ++(o: StageOutput): StageOutput =
    StageOutput(facts ++ o.facts, notes ++ o.notes, abstentions ++ o.abstentions)
}
object StageOutput {
  val empty: StageOutput = StageOutput(Nil, Nil, Nil)

  /** A stage output with one coded abstention and nothing else. */
  def abstain(code: String, text: String): StageOutput =
    StageOutput(Nil, Nil, List(Abstention(code, text)))
}

/** Fixed texts of store and retrieval failures (C-02): a stage never puts a store, SDK or provider
  * message into a note or an abstention, because both reach the answer. Names and window labels
  * come from evidence and configuration only.
  */
object StageFailures {
  val StoreFailure = "store failure"
  def metrics(name: String, window: String): String =
    s"the stored metrics of $name in window $window could not be read ($StoreFailure)"
  def allWindows(name: String): String =
    s"the stored metrics of $name could not be read ($StoreFailure)"
  def ranking(what: String, window: String): String =
    s"the stored ranking by $what in $window could not be read ($StoreFailure)"
  val TopicCandidates = "topic candidates could not be retrieved (semantic retrieval failure)"
  def topicMetrics(n: Int): String =
    s"the metrics of $n topic candidate(s) could not be read ($StoreFailure), so no topic ranking is given"
  val PathSearch = s"the path search failed (graph $StoreFailure)"
  val EdgeLookup = s"the edge lookup failed (graph $StoreFailure)"
  val NeighborLookup = s"the neighbor lookup failed (graph $StoreFailure)"
  val SemanticUnavailable = "Semantic context unavailable (semantic retrieval failure)."
  def neighborEdges(n: Int, name: String): String =
    s"$n direct collaborator edge(s) of $name could not be read ($StoreFailure); the list is incomplete."
  def bridgeCcd(n: Int): String =
    s"The coauthors in other communities of $n ranked researcher(s) could not be read ($StoreFailure); those claims leave them out."
  def neighborCommunities(n: Int, name: String): String =
    s"$n read(s) for the communities of ${name}'s top coauthors failed ($StoreFailure); the list may be incomplete."
}

/** Per-query record of what the retrieval stages touched, for QueryAudit v2: graph query-template
  * IDs, vector indexes queried (similarity queries and key lookups), metadata filter keys (never
  * values) and in-process cache hits by fixed cache name ([[QueryTrace.CacheNames]]). Safe to
  * update from several threads; one instance per query.
  */
final class QueryTrace {
  private val templates = mutable.LinkedHashSet.empty[String]
  private val indexes = mutable.LinkedHashSet.empty[String]
  private val filters = mutable.LinkedHashSet.empty[String]
  private val hits = mutable.LinkedHashMap.empty[String, Long]

  def template(id: String): Unit = synchronized { templates += id; () }
  def index(name: String): Unit = synchronized { indexes += name; () }
  def filterKeys(keys: List[String]): Unit = synchronized { filters ++= keys; () }
  def cacheHit(name: String): Unit = synchronized {
    hits(name) = hits.getOrElse(name, 0L) + 1L
  }

  def templateIds: List[String] = synchronized(templates.toList)
  def indexesQueried: List[String] = synchronized(indexes.toList)
  def metadataFilterKeys: List[String] = synchronized(filters.toList)
  def cacheHits: Map[String, Long] = synchronized(hits.toMap)

  /** `stores` with its graph and vector ports recording into this trace. */
  def wrap(stores: RuntimeStores): RuntimeStores = {
    val trace = this
    val graphPort = new GraphReadPort {
      def settings: GraphReadSettings = stores.graph.settings
      def run(q: GraphQuery): Either[String, GraphResult] = {
        trace.template(q.templateId)
        stores.graph.run(q)
      }
    }
    val vectorPort = new VectorIndexPort {
      private val base = stores.vectors
      def describeIndex(index: String) = base.describeIndex(index)
      def putVectors(index: String, vectors: Seq[VectorRecord]): Unit =
        base.putVectors(index, vectors)
      def getVectors(index: String, keys: Seq[String], returnData: Boolean) = {
        trace.index(index)
        base.getVectors(index, keys, returnData)
      }
      def query(
          index: String,
          vector: Array[Float],
          topK: Int,
          filter: Option[MetadataFilter],
          returnMetadata: Boolean
      ): List[VectorHit] = {
        trace.index(index)
        filter.foreach(f => trace.filterKeys(MetadataFilter.keys(f).toList.sorted))
        base.query(index, vector, topK, filter, returnMetadata)
      }
    }
    new RuntimeStores {
      def settings = stores.settings
      def windowLabels: List[String] = stores.windowLabels
      def graph: GraphReadPort = graphPort
      def metrics = stores.metrics
      def entityLinks = stores.entityLinks
      def evidence = stores.evidence
      def audits = stores.audits
      def vectors: VectorIndexPort = vectorPort
      def textStore = stores.textStore
    }
  }
}

object QueryTrace {
  val SemanticContextCache = "semantic-context"
  val ResearcherNameCache = "researcher-name"
  val CacheNames: List[String] = List(SemanticContextCache, ResearcherNameCache)
}

private[runtime] object StageSupport {
  def source(e: MetricCacheEntry): SourceRef =
    SourceRef(e.recordRef, e.runId, e.policyVersion, e.snapshotId)
  def metricFact(
      r: ResearcherRef,
      e: MetricCacheEntry,
      m: CollaborationMetric,
      priority: Int,
      rank: Option[Int] = None
  ): Option[MetricFact] =
    e.value(m).map { v =>
      val group = m match {
        case CollaborationMetric.CommunitySize => Some(e.communityId)
        case CollaborationMetric.ComponentSize => Some(e.componentId)
        case _                                 => None
      }
      MetricFact(r, e.windowLabel, e.window, m, v, rank, group, source(e), priority)
    }
}

/** Stage 3, CollaborationMetrics: exact stored values from the ResearcherMetricCache (RM1, RM2) and
  * rankings from the RM4 ranking items, each with its window, Spark run ID and policy version.
  * Handles collaboration_breadth, researcher_lookup, researcher_ranking (optionally topic-filtered
  * through semantic profile candidates), max_team_size (M_r and its works through WorkById) and
  * temporal_comparison.
  */
final class CollaborationMetrics(
    stores: RuntimeStores,
    ctx: RuntimeContext,
    semantic: SemanticContextStage
) {
  import StageSupport._
  import CollaborationMetric._

  val BreadthMetrics: List[CollaborationMetric] =
    List(
      PublicationCount,
      DistinctCoauthors,
      TotalCoauthorOccurrences,
      AverageCoauthorsPerPaper,
      MaxCoauthorsOnOnePaper,
      WeightedDegree
    )
  val TemporalMetrics: List[CollaborationMetric] =
    List(PublicationCount, DistinctCoauthors, WeightedDegree, TotalCoauthorOccurrences)
  val MaxWorks = 5

  def run(steps: List[PlannedStep]): StageOutput =
    steps.map(step).foldLeft(StageOutput.empty)(_ ++ _)

  private def entry(r: ResearcherRef, w: String): Either[Abstention, MetricCacheEntry] =
    stores.metrics
      .get(r.id, w)
      .left
      .map(_ =>
        Abstention(AbstentionCodes.MetricsReadFailed, StageFailures.metrics(r.displayName, w))
      )
      .flatMap(
        _.toRight(
          Abstention(
            AbstentionCodes.MetricsMissing,
            s"${r.displayName} has no stored metrics in window $w"
          )
        )
      )

  private def step(s: PlannedStep): StageOutput = s.operation match {
    case QueryOperation.CollaborationBreadth | QueryOperation.ResearcherLookup =>
      s.researchers.zipWithIndex
        .map { case (r, i) =>
          entry(r, s.windowLabel) match {
            case Left(e)  => StageOutput(Nil, Nil, List(e))
            case Right(e) =>
              StageOutput(BreadthMetrics.flatMap(m => metricFact(r, e, m, 30 + i)), Nil, Nil)
          }
        }
        .foldLeft(StageOutput.empty)(_ ++ _)

    case QueryOperation.MaxTeamSize =>
      s.researchers.zipWithIndex
        .map { case (r, i) =>
          entry(r, s.windowLabel) match {
            case Left(e)  => StageOutput(Nil, Nil, List(e))
            case Right(e) =>
              val m = metricFact(r, e, MaxCoauthorsOnOnePaper, 30 + i).toList
              val works = e.mrWorkIds.take(MaxWorks).flatMap { w =>
                stores.graph
                  .run(GraphQuery.WorkById(w))
                  .toOption
                  .flatMap(_.rows.headOption)
                  .flatMap { row =>
                    for {
                      title <- GraphRows.str(row, "displayName")
                      year <- GraphRows.num(row, "year")
                      team <- GraphRows.num(row, "teamSize")
                    } yield WorkFact(
                      w,
                      title,
                      year.toInt,
                      GraphRows.str(row, "venueKey").getOrElse("unknown"),
                      team.toInt,
                      GraphRows.str(row, "dblpKey").getOrElse(""),
                      Some(r),
                      SourceRef(
                        s"graph:Work/${w.value}",
                        ctx.graphRunId,
                        e.policyVersion,
                        e.snapshotId
                      ),
                      60 + i
                    )
                  }
              }
              val missing = e.mrWorkIds.take(MaxWorks).size - works.size
              val notes =
                (if (e.mrWorkIds.size > MaxWorks)
                   List(
                     s"${r.displayName} reaches M on more works than are listed; the first $MaxWorks are shown."
                   )
                 else Nil) ++
                  (if (missing > 0)
                     List(
                       s"$missing work record(s) behind M for ${r.displayName} could not be read from the graph."
                     )
                   else Nil)
              StageOutput(
                m ++ works,
                notes,
                if (m.isEmpty)
                  List(
                    Abstention(
                      AbstentionCodes.MetricsMissing,
                      s"${r.displayName} has no stored M in ${s.windowLabel}"
                    )
                  )
                else Nil
              )
          }
        }
        .foldLeft(StageOutput.empty)(_ ++ _)

    case QueryOperation.TemporalComparison =>
      s.researchers.zipWithIndex
        .map { case (r, i) =>
          stores.metrics.allWindows(r.id) match {
            case Left(_) =>
              StageOutput.abstain(
                AbstentionCodes.MetricsReadFailed,
                StageFailures.allWindows(r.displayName)
              )
            case Right(entries) =>
              val wanted =
                if (s.comparisonWindows.nonEmpty) s.comparisonWindows
                else ctx.windowLabels.filterNot(_ == ctx.fullWindowLabel)
              val chosen = entries.filter(e => wanted.contains(e.windowLabel))
              val metrics = s.metric.map(List(_)).getOrElse(TemporalMetrics)
              val missing = wanted.filterNot(w => chosen.exists(_.windowLabel == w))
              StageOutput(
                chosen.flatMap(e => metrics.flatMap(m => metricFact(r, e, m, 30 + i))),
                missing.map(w =>
                  s"${r.displayName} has no stored metrics in window $w (no publications in that window)."
                ),
                if (chosen.isEmpty)
                  List(
                    Abstention(
                      AbstentionCodes.MetricsMissing,
                      s"${r.displayName} has no stored metrics in the compared windows"
                    )
                  )
                else Nil
              )
          }
        }
        .foldLeft(StageOutput.empty)(_ ++ _)

    case QueryOperation.ResearcherRanking =>
      val metric = s.metric.getOrElse(DistinctCoauthors)
      s.topic match {
        case None =>
          stores.metrics.ranking(s.windowLabel, metric, s.limit) match {
            case Left(_) =>
              StageOutput.abstain(
                AbstentionCodes.RankingReadFailed,
                StageFailures.ranking(metric.wire, s.windowLabel)
              )
            case Right(rows) =>
              StageOutput(
                rows.map(x =>
                  MetricFact(
                    ResearcherRef(x.researcherId, x.displayName),
                    x.windowLabel,
                    ctx.yearWindow(x.windowLabel).get,
                    x.metric,
                    x.value,
                    Some(x.rank),
                    None,
                    SourceRef(x.recordRef, x.runId, x.policyVersion, x.runId.value),
                    40 + x.rank
                  )
                ),
                Nil,
                if (rows.isEmpty)
                  List(
                    Abstention(
                      AbstentionCodes.RankingMissing,
                      s"no ranking is stored for ${metric.wire} in ${s.windowLabel}"
                    )
                  )
                else Nil
              )
          }
        case Some(topic) => topicRanking(s, metric, topic)
      }

    case _ => StageOutput.empty
  }

  /** Top `vectors.queryTopK` profiles for the topic within the window, ranked by the metric from
    * the metric cache (value descending, then researcherId; bridge tie-break for
    * communities_connected).
    */
  private def topicRanking(
      s: PlannedStep,
      metric: CollaborationMetric,
      topic: String
  ): StageOutput =
    semantic.profileCandidates(topic, s.windowLabel) match {
      case Left(_) =>
        StageOutput.abstain(AbstentionCodes.TopicCandidatesFailed, StageFailures.TopicCandidates)
      case Right(cands) =>
        val ids = cands.flatMap(_.researcherId).distinct
        val reads = ids.map(id => stores.metrics.get(id, s.windowLabel))
        // C-05: a candidate whose metrics could not be read would shift every rank below it, so
        // a failed read gives no topic ranking at all, and the answer says why
        val failed = reads.count(_.isLeft)
        val entries = reads.flatMap(_.toOption.flatten)
        val names = cands.flatMap(c => c.researcherId.map(_ -> c)).toMap
        val ranked = entries
          .flatMap(e => e.value(metric).map(v => (e, v)))
          .sortWith { case ((a, va), (b, vb)) =>
            RankingOrder.compare(
              metric,
              RankedEntry(
                a.researcherId.value,
                va,
                a.value(CrossCommunityDegree),
                a.runId.value,
                a.policyVersion
              ),
              RankedEntry(
                b.researcherId.value,
                vb,
                b.value(CrossCommunityDegree),
                b.runId.value,
                b.policyVersion
              )
            ) < 0
          }
          .take(s.limit)
        val displayNames = ranked.map { case (e, _) =>
          e.researcherId -> displayName(e.researcherId)
        }.toMap
        val facts = ranked.zipWithIndex.flatMap { case ((e, _), i) =>
          metricFact(
            ResearcherRef(e.researcherId, displayNames(e.researcherId)),
            e,
            metric,
            40 + i + 1,
            Some(i + 1)
          )
        }
        val _ = names
        if (failed > 0)
          StageOutput.abstain(
            AbstentionCodes.TopicMetricsFailed,
            StageFailures.topicMetrics(failed)
          )
        else
          StageOutput(
            facts,
            List(
              s"Topic filter: researchers were matched to the topic by researcher-profile similarity within ${s.windowLabel} (top ${ctx.vectorTopK} profiles), not by topic labels; ranks are positions among those candidates."
            ),
            if (facts.isEmpty)
              List(
                Abstention(
                  AbstentionCodes.TopicNoMatch,
                  s"no researcher profile in ${s.windowLabel} matched the topic"
                )
              )
            else Nil
          )
    }

  private def displayName(id: ResearcherId): String =
    stores.graph
      .run(GraphQuery.ResearcherById(id))
      .toOption
      .flatMap(_.rows.headOption)
      .flatMap(GraphRows.propStr(_, "displayName"))
      .getOrElse(id.value)
}

/** Stage 4, PathFinder: bounded parameterized graph templates. shortest_path uses the precomputed
  * Spark path evidence when the pair and window match one (found through the path document's
  * deterministic vector key and its `sourceEvidenceId`, then read from the EvidenceBundle table);
  * otherwise `PathBetween` (iterative deepening up to the step's depth, bounded by
  * `runtime.maxPathDepth`, `runtime.graphTimeoutMillis`). Hop counts come only from the path result
  * or the path evidence item, never from `VectorMetadata.pathLength`. Every hop gets its edge from
  * `CoauthorEdgeBetween`. coauthor_neighborhood uses `CoauthorNeighbors` (with its truncated flag)
  * for one researcher, or `CoauthorEdgeBetween` for two.
  */
final class PathFinder(
    stores: RuntimeStores,
    ctx: RuntimeContext,
    pathIndex: String,
    trace: QueryTrace = new QueryTrace
) {
  val MaxHopEdges = 6
  val MaxSupportingWorks = 5

  def run(steps: List[PlannedStep]): StageOutput =
    steps.map(step).foldLeft(StageOutput.empty)(_ ++ _)

  private def notLoaded(s: PlannedStep): Option[StageOutput] =
    if (ctx.graphWindowLabels.contains(s.windowLabel)) None
    else
      Some(
        StageOutput.abstain(
          AbstentionCodes.WindowNotLoaded,
          s"coauthor edges for window ${s.windowLabel} are not loaded in this environment"
        )
      )

  private def step(s: PlannedStep): StageOutput = s.operation match {
    case QueryOperation.ShortestPath         => notLoaded(s).getOrElse(shortestPath(s))
    case QueryOperation.CoauthorNeighborhood => notLoaded(s).getOrElse(neighbors(s))
    case _                                   => StageOutput.empty
  }

  private val nameCache = mutable.HashMap.empty[ResearcherId, String]
  private def ref(id: ResearcherId, known: List[ResearcherRef]): ResearcherRef =
    known.find(_.id == id).getOrElse {
      if (nameCache.contains(id)) trace.cacheHit(QueryTrace.ResearcherNameCache)
      val n = nameCache.getOrElseUpdate(
        id,
        stores.graph
          .run(GraphQuery.ResearcherById(id))
          .toOption
          .flatMap(_.rows.headOption)
          .flatMap(GraphRows.propStr(_, "displayName"))
          .getOrElse(id.value)
      )
      ResearcherRef(id, n)
    }

  /** The edge between two researchers in a window, as a fact (or None when there is none). */
  def edge(
      a: ResearcherRef,
      b: ResearcherRef,
      w: String,
      priority: Int
  ): Either[String, Option[EdgeFact]] =
    stores.graph
      .run(GraphQuery.CoauthorEdgeBetween(a.id, b.id, w))
      .map(_.rows.headOption.flatMap { row =>
        for {
          edgeId <- GraphRows.str(row, "edgeId")
          weight <- GraphRows.propNum(row, "weight")
          jpc <- GraphRows.propNum(row, "jointPublicationCount")
          first <- GraphRows.propNum(row, "firstCollaborationYear")
          last <- GraphRows.propNum(row, "lastCollaborationYear")
        } yield EdgeFact(
          a,
          b,
          w,
          ctx.yearWindow(w).get,
          edgeId,
          weight,
          jpc.toLong,
          first.toInt,
          last.toInt,
          GraphRows
            .propStr(row, "supportingWorkIds")
            .map(_.split(';').toList.filter(_.nonEmpty))
            .getOrElse(Nil)
            .take(MaxSupportingWorks),
          SourceRef(
            s"graph:COAUTHORED_WITH/$edgeId",
            GraphRows
              .propStr(row, "runId")
              .flatMap(x => RunId.parse(x).toOption)
              .getOrElse(ctx.graphRunId),
            GraphRows.propStr(row, "policyVersion").getOrElse("graph-v1"),
            GraphRows.propStr(row, "snapshotId").getOrElse(ctx.graphRunId.value)
          ),
          priority
        )
      })

  /** The Spark path evidence item for the pair and window, if one of the precomputed paths matches.
    */
  def precomputed(a: ResearcherId, b: ResearcherId, w: String): Option[EvidenceItem] = {
    val window = ctx.yearWindow(w)
    List((a, b), (b, a)).view.flatMap { case (x, y) =>
      VectorKeys
        .derive(SemanticDocumentType.CollaborationPath, Some(w), List(x.value, y.value), 0)
        .toOption
        .flatMap { key =>
          scala.util
            .Try(stores.vectors.getVectors(pathIndex, List(key), returnData = false))
            .toOption
            .toList
            .flatten
            .headOption
            .flatMap(rec => VectorMetadataCodec.decode(rec.key, pathIndex, rec.metadata).toOption)
            .flatMap(_.sourceEvidenceId)
            .flatMap(id => stores.evidence.get(id, window).toOption.flatten)
            .filter(i =>
              i.evidenceType == EvidenceType.GraphPath && i.subjectIds.size >= 2 &&
                Set(i.subjectIds.head, i.subjectIds.last) == Set(a.value, b.value) &&
                i.subjectIds.forall(ResearcherId.isWellFormed)
            )
        }
    }.headOption
  }

  private def shortestPath(s: PlannedStep): StageOutput = s.researchers match {
    case List(a, b) if a.id == b.id =>
      StageOutput.abstain(
        AbstentionCodes.PathSameResearcher,
        "a path needs two different researchers"
      )
    case List(a, b) =>
      val window = ctx.yearWindow(s.windowLabel).get
      val fromSpark = precomputed(a.id, b.id, s.windowLabel)
      val pathIds: Either[
        String,
        Either[PathAbsentFact, (List[ResearcherId], String, Option[EvidenceId], Boolean, SourceRef)]
      ] =
        fromSpark match {
          case Some(item) =>
            val ids0 = item.subjectIds.map(ResearcherId(_))
            val ids = if (ids0.head == a.id) ids0 else ids0.reverse
            Right(
              Right(
                (
                  ids,
                  "precomputed-spark-path",
                  Some(item.evidenceId),
                  false,
                  SourceRef(
                    s"EvidenceBundle:${item.evidenceId.value}",
                    item.provenance.runId,
                    item.provenance.policyVersion,
                    item.provenance.snapshotId
                  )
                )
              )
            )
          case None =>
            stores.graph.run(GraphQuery.PathBetween(a.id, b.id, s.windowLabel, s.maxDepth)).map {
              r =>
                r.rows.headOption match {
                  case Some(row) =>
                    val ids = GraphRows.strList(row, "researcherIds").map(ResearcherId(_))
                    Right(
                      (
                        ids,
                        "graph-path-search",
                        None,
                        r.truncated,
                        SourceRef(
                          s"graph:${r.templateId}",
                          ctx.graphRunId,
                          "graph-v1",
                          ctx.graphRunId.value
                        )
                      )
                    )
                  case None =>
                    Left(
                      PathAbsentFact(
                        a,
                        b,
                        s.windowLabel,
                        window,
                        s.maxDepth,
                        r.complete,
                        r.depthSearched.getOrElse(0),
                        SourceRef(
                          s"graph:${r.templateId}",
                          ctx.graphRunId,
                          "graph-v1",
                          ctx.graphRunId.value
                        ),
                        20
                      )
                    )
                }
            }
        }
      pathIds match {
        case Left(_) =>
          StageOutput.abstain(AbstentionCodes.PathQueryFailed, StageFailures.PathSearch)
        case Right(Left(absent)) =>
          val note =
            if (absent.complete)
              s"No path between ${a.displayName} and ${b.displayName} exists within depth ${absent.maxDepth} in ${s.windowLabel}."
            else
              s"The path search between ${a.displayName} and ${b.displayName} stopped at the time limit after depth ${absent.depthSearched}; whether a path exists is unknown."
          StageOutput(List(absent), List(note), Nil)
        case Right(Right((ids, origin, sparkId, truncated, src))) =>
          if (ids.size < 2 || ids.exists(!_.isWellFormed) || ids.head != a.id || ids.last != b.id)
            StageOutput.abstain(
              AbstentionCodes.PathResultInvalid,
              "the stored path does not connect the two researchers"
            )
          else {
            val refs = ids.map(ref(_, s.researchers))
            val hops = refs.zip(refs.tail).take(MaxHopEdges).zipWithIndex.map { case ((x, y), i) =>
              edge(x, y, s.windowLabel, 21 + i)
            }
            val edges = hops.collect { case Right(Some(e)) => e }
            val missing = hops.size - edges.size
            StageOutput(
              PathFact(refs, s.windowLabel, window, origin, sparkId, truncated, src, 20) :: edges,
              (if (truncated) List("More than one shortest path exists; one is shown.") else Nil) ++
                (if (missing > 0) List(s"$missing hop edge(s) could not be read from the graph.")
                 else Nil),
              Nil
            )
          }
      }
    case _ =>
      StageOutput.abstain(
        AbstentionCodes.PathNeedsTwoResearchers,
        "a shortest path needs exactly two resolved researchers"
      )
  }

  private def neighbors(s: PlannedStep): StageOutput = s.researchers match {
    case List(a, b) =>
      edge(a, b, s.windowLabel, 20) match {
        case Left(_) =>
          StageOutput.abstain(AbstentionCodes.EdgeLookupFailed, StageFailures.EdgeLookup)
        case Right(Some(e)) => StageOutput(List(e), Nil, Nil)
        case Right(None)    =>
          StageOutput(
            List(
              EdgeAbsentFact(
                a,
                b,
                s.windowLabel,
                ctx.yearWindow(s.windowLabel).get,
                SourceRef(
                  "graph:coauthor_edge_between",
                  ctx.graphRunId,
                  "graph-v1",
                  ctx.graphRunId.value
                ),
                20
              )
            ),
            List(
              s"${a.displayName} and ${b.displayName} have no direct coauthor edge in ${s.windowLabel}."
            ),
            Nil
          )
      }
    case a :: Nil =>
      val limit = math.min(s.limit, ctx.runtime.graphResultLimit)
      stores.graph.run(GraphQuery.CoauthorNeighbors(a.id, s.windowLabel, limit)) match {
        case Left(_) =>
          StageOutput.abstain(AbstentionCodes.NeighborLookupFailed, StageFailures.NeighborLookup)
        case Right(r) =>
          val refs = r.rows.flatMap { row =>
            GraphRows
              .str(row, "coauthorId")
              .map(ResearcherId(_))
              .filter(_.isWellFormed)
              .map(id => ResearcherRef(id, GraphRows.str(row, "displayName").getOrElse(id.value)))
          }
          val reads = refs.zipWithIndex.map { case (b, i) => edge(a, b, s.windowLabel, 50 + i) }
          val edges = reads.flatMap(_.toOption.flatten)
          // C-05: a failed edge read shrinks the list; the answer says so
          val failed = reads.count(_.isLeft)
          StageOutput(
            edges,
            (if (failed > 0) List(StageFailures.neighborEdges(failed, a.displayName)) else Nil) ++
              (if (r.truncated)
                 List(
                   s"${a.displayName} has more direct collaborators in ${s.windowLabel} than the $limit listed (by edge weight)."
                 )
               else Nil) ++
              (if (refs.isEmpty)
                 List(s"${a.displayName} has no coauthor edges in ${s.windowLabel}.")
               else Nil),
            Nil
          )
      }
    case _ =>
      StageOutput.abstain(
        AbstentionCodes.CollaborationNeedsResearchers,
        "direct collaboration needs one or two resolved researchers"
      )
  }
}

/** Stage 5, CommunityAnalyst: community ID and size, cross-community degree, communities connected,
  * participation coefficient, component ID and size (RM1), the community of the top coauthors, and
  * bridge rankings (RM4 `communities_connected`, ties by cross-community degree then researcherId).
  * No betweenness is computed; communities are approximate (label propagation did not fully
  * settle).
  */
final class CommunityAnalyst(stores: RuntimeStores, ctx: RuntimeContext, paths: PathFinder) {
  import StageSupport._
  import CollaborationMetric._
  val NeighborCommunities = 5

  val Caveat: String =
    "Bridge position is measured by the number of other communities a researcher's coauthors belong to (communities connected) and the cross-community degree; no betweenness centrality is computed, and communities come from label propagation that did not fully settle, so they are approximate."

  def run(steps: List[PlannedStep]): StageOutput =
    steps.map(step).foldLeft(StageOutput.empty)(_ ++ _)

  private def step(s: PlannedStep): StageOutput = s.operation match {
    case QueryOperation.ConnectedComponent =>
      s.researchers.zipWithIndex
        .map { case (r, i) =>
          stores.metrics.get(r.id, s.windowLabel) match {
            case Right(Some(e)) =>
              StageOutput(metricFact(r, e, ComponentSize, 30 + i).toList, Nil, Nil)
            case Right(None) => noMetrics(r, s.windowLabel)
            case Left(_)     => metricsFailed(r, s.windowLabel)
          }
        }
        .foldLeft(StageOutput.empty)(_ ++ _)

    case QueryOperation.CommunityAnalysis =>
      s.researchers.zipWithIndex
        .map { case (r, i) =>
          stores.metrics.get(r.id, s.windowLabel) match {
            case Right(Some(e)) =>
              val own = List(
                CommunitySize,
                DistinctCoauthors,
                CrossCommunityDegree,
                CommunitiesConnected,
                ParticipationCoefficient,
                ComponentSize
              )
                .flatMap(m => metricFact(r, e, m, 30 + i))
              val (nbFacts, nbFailed) = neighborCommunities(r, e, s.windowLabel)
              StageOutput(
                own ++ nbFacts,
                List(Caveat) ++
                  (if (nbFailed > 0)
                     List(StageFailures.neighborCommunities(nbFailed, r.displayName))
                   else Nil),
                Nil
              )
            case Right(None) => noMetrics(r, s.windowLabel)
            case Left(_)     => metricsFailed(r, s.windowLabel)
          }
        }
        .foldLeft(StageOutput.empty)(_ ++ _)

    case QueryOperation.BridgeResearchers =>
      stores.metrics.ranking(s.windowLabel, CommunitiesConnected, s.limit) match {
        case Left(_) =>
          StageOutput.abstain(
            AbstentionCodes.RankingReadFailed,
            StageFailures.ranking(CommunitiesConnected.wire, s.windowLabel)
          )
        case Right(rows) =>
          var ccdFailed = 0
          val facts = rows.flatMap { x =>
            val r = ResearcherRef(x.researcherId, x.displayName)
            val main = MetricFact(
              r,
              x.windowLabel,
              ctx.yearWindow(x.windowLabel).get,
              x.metric,
              x.value,
              Some(x.rank),
              None,
              SourceRef(x.recordRef, x.runId, x.policyVersion, x.runId.value),
              40 + x.rank
            )
            val read = stores.metrics.get(x.researcherId, x.windowLabel)
            // C-05: a failed read is counted and stated, never silently dropped
            if (read.isLeft) ccdFailed += 1
            val ccd = read.toOption.flatten
              .flatMap(e => metricFact(r, e, CrossCommunityDegree, 70 + x.rank))
            main :: ccd.toList
          }
          StageOutput(
            facts,
            List(Caveat) ++
              (if (ccdFailed > 0) List(StageFailures.bridgeCcd(ccdFailed)) else Nil),
            if (rows.isEmpty)
              List(
                Abstention(
                  AbstentionCodes.RankingMissing,
                  s"no bridge ranking is stored for ${s.windowLabel}"
                )
              )
            else Nil
          )
      }
    case _ => StageOutput.empty
  }

  private def noMetrics(r: ResearcherRef, w: String): StageOutput =
    StageOutput.abstain(
      AbstentionCodes.MetricsMissing,
      s"${r.displayName} has no stored metrics in window $w"
    )

  private def metricsFailed(r: ResearcherRef, w: String): StageOutput =
    StageOutput.abstain(AbstentionCodes.MetricsReadFailed, StageFailures.metrics(r.displayName, w))

  /** Community of up to five top coauthors (by edge weight) that sit in another community, and the
    * number of reads that failed (C-05: stated in a note, never silently dropped).
    */
  private def neighborCommunities(
      r: ResearcherRef,
      e: MetricCacheEntry,
      w: String
  ): (List[Fact], Int) =
    if (!ctx.graphWindowLabels.contains(w)) (Nil, 0)
    else
      stores.graph.run(
        GraphQuery
          .CoauthorNeighbors(r.id, w, math.min(NeighborCommunities, ctx.runtime.graphResultLimit))
      ) match {
        case Left(_)    => (Nil, 1)
        case Right(res) =>
          var failed = 0
          val facts = res.rows.flatMap { row =>
            GraphRows.str(row, "coauthorId").map(ResearcherId(_)).filter(_.isWellFormed) match {
              case None     => Nil
              case Some(id) =>
                stores.metrics.get(id, w) match {
                  case Left(_)                                            => failed += 1; Nil
                  case Right(None)                                        => Nil
                  case Right(Some(ne)) if ne.communityId == e.communityId => Nil
                  case Right(Some(ne))                                    =>
                    val nb =
                      ResearcherRef(id, GraphRows.str(row, "displayName").getOrElse(id.value))
                    metricFact(nb, ne, CommunitySize, 80) match {
                      case None    => Nil
                      case Some(f) =>
                        paths.edge(r, nb, w, 80) match {
                          case Left(_)        => failed += 1; Nil
                          case Right(None)    => Nil
                          case Right(Some(x)) => List(x, f)
                        }
                    }
                }
            }
          }
          (facts, failed)
      }
}

/** A semantic hit with the researcher it describes (profiles only). */
final case class SemanticHit(entry: SemanticContextEntry, researcherId: Option[ResearcherId])

/** Stage 6, SemanticContext: rag's [[SemanticRetriever]], always with a window filter on windowed
  * indexes. The result block is validated against `semantic-context-v1`; text is fetched through
  * `textS3Uri` and checked against `textHash`, then truncated and passed to the model only as
  * marked untrusted data. Semantic facts are pointers; they never support a number, edge, path,
  * component, community or bridge claim.
  */
final class SemanticContextStage(
    retriever: Option[SemanticRetriever],
    stores: RuntimeStores,
    ctx: RuntimeContext,
    trace: QueryTrace = new QueryTrace
) {
  val MaxFacts = 5
  val ExcerptChars = 400
  private val cache = mutable.HashMap.empty[(String, String), Either[String, List[SemanticHit]]]

  /** Profiles most similar to `topic` within the window (at most `vectors.queryTopK`). */
  def profileCandidates(topic: String, windowLabel: String): Either[String, List[SemanticHit]] = {
    if (cache.contains((topic, windowLabel))) trace.cacheHit(QueryTrace.SemanticContextCache)
    cache.getOrElseUpdate(
      (topic, windowLabel),
      query(SemanticDocumentType.ResearcherProfile, topic, windowLabel)
    )
  }

  private def query(
      t: SemanticDocumentType,
      text: String,
      windowLabel: String
  ): Either[String, List[SemanticHit]] =
    for {
      r <- retriever.toRight("semantic retrieval is not configured")
      w <- ctx.yearWindow(windowLabel).toRight(s"window $windowLabel is not configured")
      res <- r.retrieve(
        t,
        text,
        ctx.vectorTopK,
        Some(SemanticRetriever.windowFilter(w.startYear, w.endYear))
      )
      _ = {
        trace.index(res.context.query.indexName)
        trace.filterKeys(res.context.query.filterKeys)
      }
      _ <- SemanticContextSchema
        .validate(res.context.toJson)
        .left
        .map(e => s"semantic context failed semantic-context-v1: $e")
    } yield res.context.entries.map(e =>
      SemanticHit(e, e.subjectIds.headOption.map(ResearcherId(_)).filter(_.isWellFormed))
    )

  def run(steps: List[PlannedStep]): StageOutput =
    steps
      .collect {
        case s
            if s.operation == QueryOperation.SemanticContext || (s.operation == QueryOperation.ResearcherRanking && s.topic.isDefined) =>
          val text = s.topic.orElse(
            Some(s.researchers.map(_.displayName).mkString(" ")).filter(_.trim.nonEmpty)
          )
          text match {
            case None =>
              StageOutput.abstain(
                AbstentionCodes.SemanticNeedsTopicOrName,
                "semantic context needs a topic or a researcher name"
              )
            case Some(q) =>
              profileCandidates(q.take(SemanticRetriever.MaxQueryChars), s.windowLabel) match {
                case Left(_)     => StageOutput(Nil, List(StageFailures.SemanticUnavailable), Nil)
                case Right(hits) =>
                  val facts = hits.take(MaxFacts).zipWithIndex.map { case (h, i) =>
                    val excerpt = stores.textStore
                      .fetch(h.entry.textS3Uri, h.entry.textHash)
                      .toOption
                      .map(t => Prompts.asData(t, ExcerptChars))
                    SemanticFact(h.entry, h.researcherId, excerpt, 90 + i)
                  }
                  StageOutput(facts, Nil, Nil)
              }
          }
      }
      .foldLeft(StageOutput.empty)(_ ++ _)
}

private[runtime] object MetaFilters {
  def eq(k: String, v: Long): MetadataFilter = MetadataFilter.Eq(k, MetaValue.Num(v))
}
