package org.scholarchain.spark

import java.util.Locale

import org.apache.spark.sql.{Dataset, SparkSession}
import org.apache.spark.sql.functions._
import org.scholarchain.core._

final case class ProfileInput(
    researcherId: String,
    displayName: String,
    identityOutcome: String,
    p: Long,
    c: Long,
    t: Long,
    m: Long,
    w: Double,
    identityConfidence: Double,
    componentSize: Long,
    communityId: String,
    communitySize: Long,
    coreness: Long,
    participation: Option[Double],
    crossDegree: Long,
    communitiesConnected: Long,
    venues: Seq[VenueCount]
)
final case class VenueCount(negCount: Long, name: String)
final case class MemberRank(negC: Long, researcherId: String, displayName: String)
final case class CommunityInput(
    communityId: String,
    size: Long,
    internalEdges: Long,
    crossEdges: Long,
    top: Seq[MemberRank]
)

/** Deterministic template summaries for later embedding. Every number and name comes from the
  * computed tables or source records; no abstracts, topics or invented facts. Each summary is an
  * EvidenceItem whose `snippet` is the text and whose `provenance.contentHash` hashes it.
  */
object Summaries {

  val JobSummaries = "spark.semantic-summaries"
  val ProfileKind = "researcher_profile"
  val CommunityKind = "community"
  val PathKind = "path"

  def fmt(d: Double): String = String.format(Locale.ROOT, "%.4f", Double.box(d))
  def win(w: YearWindow): String = s"${w.startYear}-${w.endYear}"

  def profileText(r: ProfileInput, w: YearWindow): String = {
    val a = if (r.p > 0) fmt(r.t.toDouble / r.p.toDouble) else "n/a"
    val pc = r.participation.map(fmt).getOrElse("n/a (no coauthor edges)")
    val venues =
      if (r.venues.isEmpty) "none recorded"
      else r.venues.map(v => s"${v.name} (${-v.negCount})").mkString(", ")
    s"Researcher ${r.displayName} (${r.researcherId}), window ${win(w)}. " +
      s"Publications P=${r.p}; distinct coauthors C=${r.c}; total coauthor occurrences T=${r.t}; " +
      s"average coauthors per paper A=$a; maximum coauthors on one paper M=${r.m}; " +
      s"weighted degree W=${fmt(r.w)}. Connected component size ${r.componentSize}; " +
      s"community ${r.communityId} with ${r.communitySize} members; coreness ${r.coreness}; " +
      s"participation coefficient $pc; coauthors in other communities ${r.crossDegree}; " +
      s"other communities connected ${r.communitiesConnected}. Most frequent venues: $venues. " +
      s"Identity: ${r.identityOutcome}, confidence ${fmt(r.identityConfidence)}."
  }

  def communityText(c: CommunityInput, w: YearWindow): String = {
    val top = c.top.map(m => s"${m.displayName} (${m.researcherId}, C=${-m.negC})").mkString("; ")
    s"Community ${c.communityId}, window ${win(w)}: ${c.size} researchers, " +
      s"${c.internalEdges} coauthor edges inside the community, ${c.crossEdges} coauthor edges to " +
      s"other communities. Members with the most distinct coauthors: $top."
  }

  def pathText(p: PathEvidenceRow, names: Map[String, String], w: YearWindow): String = {
    def n(id: String) = s"${names.getOrElse(id, id)} ($id)"
    val hops = p.hops
      .map { h =>
        s"${n(h.from)} to ${n(h.to)}: ${h.weight.toLong} joint publications, " +
          s"${h.firstYear}-${h.lastYear}"
      }
      .mkString("; ")
    s"Collaboration path in window ${win(w)} from ${n(p.sourceId)} to ${n(p.targetId)} with " +
      s"${p.hopCount} hops (shortest within the depth cap). Hops: $hops."
  }

  def item(
      kind: String,
      evidenceType: EvidenceType,
      subjects: Seq[String],
      metric: Option[MetricValueRow],
      text: String,
      w: YearWindow,
      ctx: ProvCtx
  ): SemanticSummaryRow =
    SemanticSummaryRow(
      kind,
      EvidenceItemRow.of(
        evidenceType,
        subjects,
        w,
        metric,
        Some(text),
        ctx.row(JobSummaries, subjects, Some(ContentHash.of(text)))
      )
    )

  /** Researcher profiles for researchers with at least `minWorks` works in the window. */
  def profiles(
      spark: SparkSession,
      facts: Dataset[ResearcherWindowFacts],
      nodes: Dataset[GraphNodeRow],
      authorship: Dataset[AuthorshipFact],
      works: Dataset[WorkIn],
      venues: Dataset[VenueIn],
      w: YearWindow,
      minWorks: Int,
      topVenues: Int,
      ctx: ProvCtx
  ): Dataset[SemanticSummaryRow] = {
    import spark.implicits._
    val venueCounts = authorship
      .where(col("year").between(w.startYear, w.endYear))
      .select("researcherId", "workId")
      .distinct()
      .join(works.toDF().select("workId", "venueKey"), "workId")
      .join(venues.toDF(), "venueKey")
      .groupBy("researcherId", "name")
      .agg(count(lit(1)).as("n"))
      .groupBy("researcherId")
      .agg(
        sort_array(collect_list(struct((lit(0L) - col("n")).as("negCount"), col("name")))).as("all")
      )
      .select(col("researcherId"), slice(col("all"), 1, math.max(topVenues, 1)).as("venues"))
    val names = nodes
      .toDF()
      .select(
        col("nodeId").as("researcherId"),
        col("displayName"),
        col("properties").getItem("identityOutcome").as("identityOutcome")
      )
    facts
      .toDF()
      .where(col("p") >= minWorks)
      .join(names, "researcherId")
      .join(venueCounts, Seq("researcherId"), "left")
      .select(
        col("researcherId"),
        col("displayName"),
        col("identityOutcome"),
        col("p"),
        col("c"),
        col("t"),
        col("m"),
        col("w"),
        col("identityConfidence"),
        col("componentSize"),
        col("communityId"),
        col("communitySize"),
        col("coreness"),
        col("participation"),
        col("crossDegree"),
        col("communitiesConnected"),
        (if (topVenues > 0) col("venues") else lit(null)).as("venues")
      )
      .as[ProfileInput]
      .map { r =>
        val fixed = r.copy(venues = Option(r.venues).getOrElse(Nil))
        item(
          ProfileKind,
          EvidenceType.Metric,
          Seq(r.researcherId),
          Some(MetricValueRow(CollaborationMetric.PublicationCount.wire, r.p.toDouble)),
          profileText(fixed, w),
          w,
          ctx
        )
      }
  }

  /** Community summaries for communities with at least `minMembers` members. */
  def communities(
      spark: SparkSession,
      facts: Dataset[ResearcherWindowFacts],
      nodes: Dataset[GraphNodeRow],
      edges: Dataset[CoauthorEdgeRow],
      w: YearWindow,
      minMembers: Int,
      topMembers: Int,
      ctx: ProvCtx
  ): Dataset[SemanticSummaryRow] = {
    import spark.implicits._
    val f = facts.toDF().where(col("communitySize") >= minMembers)
    val comm = facts.toDF().select("researcherId", "communityId")
    val e = edges
      .toDF()
      .select("left", "right")
      .join(comm.select(col("researcherId").as("left"), col("communityId").as("cl")), "left")
      .join(comm.select(col("researcherId").as("right"), col("communityId").as("cr")), "right")
    val perSide = e
      .select(col("cl").as("communityId"), (col("cl") === col("cr")).as("inside"))
      .unionByName(
        e.where(col("cl") =!= col("cr"))
          .select(col("cr").as("communityId"), lit(false).as("inside"))
      )
      .groupBy("communityId")
      .agg(
        sum(when(col("inside"), 1L).otherwise(0L)).as("internalEdges"),
        sum(when(col("inside"), 0L).otherwise(1L)).as("crossEdges")
      )
    val names = nodes.toDF().select(col("nodeId").as("researcherId"), col("displayName"))
    f.join(names, "researcherId")
      .groupBy("communityId")
      .agg(
        count(lit(1)).as("size"),
        slice(
          sort_array(
            collect_list(
              struct((lit(0L) - col("c")).as("negC"), col("researcherId"), col("displayName"))
            )
          ),
          1,
          topMembers
        ).as("top")
      )
      .join(perSide, Seq("communityId"), "left")
      .select(
        col("communityId"),
        col("size"),
        coalesce(col("internalEdges"), lit(0L)).as("internalEdges"),
        coalesce(col("crossEdges"), lit(0L)).as("crossEdges"),
        col("top")
      )
      .as[CommunityInput]
      .map { c =>
        item(
          CommunityKind,
          EvidenceType.Metric,
          c.communityId +: c.top.map(_.researcherId),
          Some(MetricValueRow(CollaborationMetric.CommunitySize.wire, c.size.toDouble)),
          communityText(c, w),
          w,
          ctx
        )
      }
  }

  /** Path summaries for every materialized path (bounded, built on the driver). */
  def paths(
      paths: Seq[PathEvidenceRow],
      names: Map[String, String],
      w: YearWindow,
      ctx: ProvCtx
  ): Seq[SemanticSummaryRow] =
    paths.map(p =>
      item(
        PathKind,
        EvidenceType.GraphPath,
        p.evidence.subjectIds,
        None,
        pathText(p, names, w),
        w,
        ctx
      )
    )
}
