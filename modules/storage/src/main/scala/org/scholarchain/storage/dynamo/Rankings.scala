package org.scholarchain.storage.dynamo

import scala.collection.immutable.ListMap
import scala.jdk.CollectionConverters._

import org.scholarchain.core.{CodePointOrder, CollaborationMetric}
import org.scholarchain.storage.ports.{AttrValue, Item}

/** The metrics cached as rankings (access pattern RM4) and the metric family each comes from. */
object RankingMetrics {
  import CollaborationMetric._

  val ranked: List[CollaborationMetric] = List(
    PublicationCount,
    DistinctCoauthors,
    TotalCoauthorOccurrences,
    AverageCoauthorsPerPaper,
    MaxCoauthorsOnOnePaper,
    WeightedDegree,
    Coreness,
    ParticipationCoefficient,
    CrossCommunityDegree,
    CommunitiesConnected
  )

  def isRanked(m: CollaborationMetric): Boolean = ranked.contains(m)

  /** The Spark metric family (export table family) that produces `m`. */
  def family(m: CollaborationMetric): String = m match {
    case Coreness                                                               => "kcore"
    case ParticipationCoefficient | CrossCommunityDegree | CommunitiesConnected => "bridges"
    case _                                                                      => "breadth"
  }

  /** Largest rank (sort key `rank#NNNN`). */
  val MaxRank: Int = 9999
}

/** One ranked researcher before display names are attached. `crossCommunityDegree` is the
  * communities_connected tie-break.
  */
final case class RankedEntry(
    researcherId: String,
    value: Double,
    crossCommunityDegree: Option[Double],
    runId: String,
    policyVersion: String
)

object RankingOrder {

  /** Negative when `a` ranks before `b`. Value descending, then researcherId by code point. For
    * `communities_connected` (the bridge order): value descending, then cross_community_degree
    * descending, then researcherId.
    */
  def compare(metric: CollaborationMetric, a: RankedEntry, b: RankedEntry): Int = {
    val byValue = java.lang.Double.compare(b.value, a.value)
    if (byValue != 0) byValue
    else {
      val byBridge =
        if (metric == CollaborationMetric.CommunitiesConnected)
          java.lang.Double.compare(
            b.crossCommunityDegree.getOrElse(Double.NegativeInfinity),
            a.crossCommunityDegree.getOrElse(Double.NegativeInfinity)
          )
        else 0
      if (byBridge != 0) byBridge else CodePointOrder.compare(a.researcherId, b.researcherId)
    }
  }
}

/** Top-`topK` researchers of one window for each ranked metric, kept in bounded heaps while the
  * metric load streams its accumulators. Only researchers that have the metric value are ranked (A
  * and the participation coefficient are optional).
  */
final class RankingAccumulator(val windowLabel: String, val topK: Int) {
  require(topK >= 1 && topK <= RankingMetrics.MaxRank, s"topK $topK")

  private val heaps: ListMap[CollaborationMetric, java.util.PriorityQueue[RankedEntry]] =
    ListMap.from(RankingMetrics.ranked.map { m =>
      // worst entry at the head, so it is the one evicted
      m -> new java.util.PriorityQueue[RankedEntry](
        topK + 1,
        (x: RankedEntry, y: RankedEntry) => -RankingOrder.compare(m, x, y)
      )
    })

  /** Offers one complete researcher-window accumulator (call only for items that were built). */
  def offer(a: MetricCacheAccumulator): Unit = {
    require(a.windowLabel == windowLabel, s"${a.windowLabel} offered to $windowLabel ranking")
    val ccd = a.values.get(CollaborationMetric.CrossCommunityDegree)
    heaps.foreach { case (m, heap) =>
      a.values.get(m).foreach { v =>
        val p = a.familyProvenance.get(RankingMetrics.family(m)).orElse(a.provenance)
        p.foreach { prov =>
          heap.add(RankedEntry(a.researcherId, v, ccd, prov.runId.value, prov.policyVersion))
          if (heap.size > topK) heap.poll()
        }
      }
    }
  }

  /** The ranking of `metric`, best first (rank 1 is the head). */
  def ranking(metric: CollaborationMetric): List[RankedEntry] =
    heaps
      .get(metric)
      .map(_.asScala.toList.sortWith((x, y) => RankingOrder.compare(metric, x, y) < 0))
      .getOrElse(Nil)

  def rankings: ListMap[CollaborationMetric, List[RankedEntry]] =
    ListMap.from(RankingMetrics.ranked.map(m => m -> ranking(m)))

  def researcherIds: Set[String] = heaps.valuesIterator.flatMap(_.asScala.map(_.researcherId)).toSet
}

object RankingItems {
  import AttrValue._

  /** One RM4 item: pk `ranking#<windowLabel>#<metric wire>`, sk `rank#<NNNN>`. */
  def item(
      windowLabel: String,
      metric: CollaborationMetric,
      rank: Int,
      e: RankedEntry,
      displayName: String
  ): Item = {
    require(rank >= 1 && rank <= RankingMetrics.MaxRank, s"rank $rank")
    Item(
      ListMap[String, AttrValue](
        Item.Pk -> S(TableSpecs.rankingPk(windowLabel, metric.wire)),
        Item.Sk -> S(TableSpecs.rankSk(rank)),
        "windowLabel" -> S(windowLabel),
        "metric" -> S(metric.wire),
        "rank" -> AttrValue.n(rank.toLong),
        "researcherId" -> S(e.researcherId),
        "displayName" -> S(displayName),
        "value" -> AttrValue.n(e.value),
        "runId" -> S(e.runId),
        "policyVersion" -> S(e.policyVersion)
      )
    )
  }
}
