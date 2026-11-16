package org.scholarchain.rag.runtime

import scala.collection.mutable

import org.scholarchain.core._
import org.scholarchain.rag.retrieval.{SemanticContextEntry, SemanticRetriever}
import org.scholarchain.storage.dynamo.RankingOrder
import org.scholarchain.storage.dynamo.RankedEntry
import org.scholarchain.storage.ports.{GraphQuery, MetaValue, MetadataFilter}
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
  * not be answered (they are stated in the answer).
  */
final case class StageOutput(facts: List[Fact], notes: List[String], abstentions: List[String]) {
  def ++(o: StageOutput): StageOutput =
    StageOutput(facts ++ o.facts, notes ++ o.notes, abstentions ++ o.abstentions)
}
object StageOutput { val empty: StageOutput = StageOutput(Nil, Nil, Nil) }

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

  private def entry(r: ResearcherRef, w: String): Either[String, MetricCacheEntry] =
    stores.metrics
      .get(r.id, w)
      .flatMap(_.toRight(s"${r.displayName} has no stored metrics in window $w"))

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
                if (m.isEmpty) List(s"${r.displayName} has no stored M in ${s.windowLabel}")
                else Nil
              )
          }
        }
        .foldLeft(StageOutput.empty)(_ ++ _)

    case QueryOperation.TemporalComparison =>
      s.researchers.zipWithIndex
        .map { case (r, i) =>
          stores.metrics.allWindows(r.id) match {
            case Left(e)        => StageOutput(Nil, Nil, List(e))
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
                  List(s"${r.displayName} has no stored metrics in the compared windows")
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
            case Left(e)     => StageOutput(Nil, Nil, List(e))
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
                  List(s"no ranking is stored for ${metric.wire} in ${s.windowLabel}")
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
      case Left(e) => StageOutput(Nil, Nil, List(s"topic candidates could not be retrieved: $e"))
      case Right(cands) =>
        val ids = cands.flatMap(_.researcherId).distinct
        val entries = ids.flatMap(id => stores.metrics.get(id, s.windowLabel).toOption.flatten)
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
        StageOutput(
          facts,
          List(
            s"Topic filter: researchers were matched to the topic by researcher-profile similarity within ${s.windowLabel} (top ${ctx.vectorTopK} profiles), not by topic labels; ranks are positions among those candidates."
          ),
          if (facts.isEmpty) List(s"no researcher profile in ${s.windowLabel} matched the topic")
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
final class PathFinder(stores: RuntimeStores, ctx: RuntimeContext, pathIndex: String) {
  val MaxHopEdges = 6
  val MaxSupportingWorks = 5

  def run(steps: List[PlannedStep]): StageOutput =
    steps.map(step).foldLeft(StageOutput.empty)(_ ++ _)

  private def notLoaded(s: PlannedStep): Option[StageOutput] =
    if (ctx.graphWindowLabels.contains(s.windowLabel)) None
    else
      Some(
        StageOutput(
          Nil,
          Nil,
          List(s"coauthor edges for window ${s.windowLabel} are not loaded in this environment")
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
      StageOutput(Nil, Nil, List("a path needs two different researchers"))
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
        case Left(e)             => StageOutput(Nil, Nil, List(s"path search failed: $e"))
        case Right(Left(absent)) =>
          val note =
            if (absent.complete)
              s"No path between ${a.displayName} and ${b.displayName} exists within depth ${absent.maxDepth} in ${s.windowLabel}."
            else
              s"The path search between ${a.displayName} and ${b.displayName} stopped at the time limit after depth ${absent.depthSearched}; whether a path exists is unknown."
          StageOutput(List(absent), List(note), Nil)
        case Right(Right((ids, origin, sparkId, truncated, src))) =>
          if (ids.size < 2 || ids.exists(!_.isWellFormed) || ids.head != a.id || ids.last != b.id)
            StageOutput(Nil, Nil, List("the stored path does not connect the two researchers"))
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
    case _ => StageOutput(Nil, Nil, List("a shortest path needs exactly two resolved researchers"))
  }

  private def neighbors(s: PlannedStep): StageOutput = s.researchers match {
    case List(a, b) =>
      edge(a, b, s.windowLabel, 20) match {
        case Left(e)        => StageOutput(Nil, Nil, List(s"edge lookup failed: $e"))
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
        case Left(e)  => StageOutput(Nil, Nil, List(s"neighbor lookup failed: $e"))
        case Right(r) =>
          val refs = r.rows.flatMap { row =>
            GraphRows
              .str(row, "coauthorId")
              .map(ResearcherId(_))
              .filter(_.isWellFormed)
              .map(id => ResearcherRef(id, GraphRows.str(row, "displayName").getOrElse(id.value)))
          }
          val edges = refs.zipWithIndex.flatMap { case (b, i) =>
            edge(a, b, s.windowLabel, 50 + i).toOption.flatten
          }
          StageOutput(
            edges,
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
      StageOutput(Nil, Nil, List("direct collaboration needs one or two resolved researchers"))
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
            case Right(None) =>
              StageOutput(
                Nil,
                Nil,
                List(s"${r.displayName} has no stored metrics in window ${s.windowLabel}")
              )
            case Left(err) => StageOutput(Nil, Nil, List(err))
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
              StageOutput(own ++ neighborCommunities(r, e, s.windowLabel), List(Caveat), Nil)
            case Right(None) =>
              StageOutput(
                Nil,
                Nil,
                List(s"${r.displayName} has no stored metrics in window ${s.windowLabel}")
              )
            case Left(err) => StageOutput(Nil, Nil, List(err))
          }
        }
        .foldLeft(StageOutput.empty)(_ ++ _)

    case QueryOperation.BridgeResearchers =>
      stores.metrics.ranking(s.windowLabel, CommunitiesConnected, s.limit) match {
        case Left(e)     => StageOutput(Nil, Nil, List(e))
        case Right(rows) =>
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
            val ccd = stores.metrics
              .get(x.researcherId, x.windowLabel)
              .toOption
              .flatten
              .flatMap(e => metricFact(r, e, CrossCommunityDegree, 70 + x.rank))
            main :: ccd.toList
          }
          StageOutput(
            facts,
            List(Caveat),
            if (rows.isEmpty) List(s"no bridge ranking is stored for ${s.windowLabel}") else Nil
          )
      }
    case _ => StageOutput.empty
  }

  /** Community of up to five top coauthors (by edge weight) that sit in another community. */
  private def neighborCommunities(r: ResearcherRef, e: MetricCacheEntry, w: String): List[Fact] =
    if (!ctx.graphWindowLabels.contains(w)) Nil
    else
      stores.graph
        .run(
          GraphQuery
            .CoauthorNeighbors(r.id, w, math.min(NeighborCommunities, ctx.runtime.graphResultLimit))
        )
        .toOption
        .toList
        .flatMap(_.rows)
        .flatMap { row =>
          (for {
            id <- GraphRows.str(row, "coauthorId").map(ResearcherId(_)).filter(_.isWellFormed)
            ne <- stores.metrics.get(id, w).toOption.flatten if ne.communityId != e.communityId
            nb = ResearcherRef(id, GraphRows.str(row, "displayName").getOrElse(id.value))
            f <- metricFact(nb, ne, CommunitySize, 80)
            edge <- paths.edge(r, nb, w, 80).toOption.flatten
          } yield List(edge, f)).getOrElse(Nil)
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
    ctx: RuntimeContext
) {
  val MaxFacts = 5
  val ExcerptChars = 400
  private val cache = mutable.HashMap.empty[(String, String), Either[String, List[SemanticHit]]]

  /** Profiles most similar to `topic` within the window (at most `vectors.queryTopK`). */
  def profileCandidates(topic: String, windowLabel: String): Either[String, List[SemanticHit]] =
    cache.getOrElseUpdate(
      (topic, windowLabel),
      query(SemanticDocumentType.ResearcherProfile, topic, windowLabel)
    )

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
              StageOutput(Nil, Nil, List("semantic context needs a topic or a researcher name"))
            case Some(q) =>
              profileCandidates(q.take(SemanticRetriever.MaxQueryChars), s.windowLabel) match {
                case Left(e)     => StageOutput(Nil, List(s"Semantic context unavailable: $e"), Nil)
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
