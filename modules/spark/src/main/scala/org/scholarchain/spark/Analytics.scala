package org.scholarchain.spark

import org.apache.spark.sql.{DataFrame, Dataset, SparkSession}
import org.apache.spark.sql.functions._
import org.scholarchain.core._
import org.scholarchain.core.config.NamedWindow

/** Every per-researcher fact of one window, before it is split into the exported tables.
  *
  * @param p
  *   P_r, distinct works in the window (single-author works included)
  * @param c
  *   C_r, distinct coauthor researchers (edge degree in the window graph)
  * @param t
  *   T_r, sum over the researcher's works of the distinct other researchers on the work
  * @param m
  *   M_r, maximum over the works of the distinct other researchers on one work
  * @param w
  *   W_r, sum of the researcher's coauthor-edge weights
  * @param identityConfidence
  *   minimum of the researcher's own authorship confidences in the window and of the
  *   minIdentityConfidence of its coauthor edges in the window (a CONFLICT cluster's confidence
  *   therefore also reaches the metrics of its coauthors, whose C_r and W_r count it)
  * @param participation
  *   participation coefficient; None when W_r = 0
  * @param crossDegree
  *   cross-community degree in edges: coauthors whose community differs from the researcher's
  */
final case class ResearcherWindowFacts(
    researcherId: String,
    p: Long,
    t: Long,
    m: Long,
    c: Long,
    w: Double,
    identityConfidence: Double,
    componentId: String,
    componentSize: Long,
    communityId: String,
    communitySize: Long,
    coreness: Long,
    participation: Option[Double],
    crossDegree: Long,
    communitiesConnected: Long,
    mWorks: Seq[WorkTeamSize]
)

object Analytics {

  val JobMetrics = "spark.researcher-metrics"
  val JobGroups = "spark.researcher-groups"
  val JobBridges = "spark.bridges"
  val JobKcore = "spark.kcore"
  val JobMetricEvidence = "spark.metric-evidence"

  /** Researcher IDs in code-point order with dense Long indexes (order preserving). */
  def vertexIndex(spark: SparkSession, researcherIds: Dataset[String]): DataFrame = {
    import spark.implicits._
    researcherIds
      .toDF("researcherId")
      .distinct()
      .orderBy(col("researcherId"))
      .as[String]
      .rdd
      .zipWithIndex()
      .map { case (r, i) => (i, r) }
      .toDF("id", "researcherId")
  }

  /** P, T, M (with its works and dblp team sizes) and the researcher's own minimum authorship
    * confidence, from the authorships of the window.
    */
  def breadth(
      authorship: Dataset[AuthorshipFact],
      teams: Dataset[TeamIn],
      window: NamedWindow
  ): DataFrame = {
    val rw = authorship
      .where(col("year").between(window.startYear, window.endYear))
      .groupBy("researcherId", "workId")
      .agg(min("confidence").as("confidence"))
    val team = rw.groupBy("workId").agg(count(lit(1)).as("n"))
    val perWork = rw
      .join(team, "workId")
      .join(teams.toDF().select(col("workId"), col("teamSize")), Seq("workId"), "left")
      .select(
        col("researcherId"),
        col("workId"),
        col("confidence"),
        (col("n") - 1).as("others"),
        col("n"),
        coalesce(col("teamSize"), lit(-1)).as("teamSize")
      )
    val agg = perWork
      .groupBy("researcherId")
      .agg(
        count(lit(1)).as("p"),
        sum("others").as("t"),
        max("others").as("m"),
        min("confidence").as("confOwn")
      )
    val mWorks = perWork
      .join(agg.select("researcherId", "m"), "researcherId")
      .where(col("others") === col("m"))
      .groupBy("researcherId")
      .agg(
        sort_array(
          collect_list(
            struct(
              col("workId"),
              col("n").cast("int").as("distinctResearchers"),
              col("teamSize").cast("int").as("dblpTeamSize")
            )
          )
        ).as("mWorks")
      )
    agg.join(mWorks, "researcherId")
  }

  /** C, W and the minimum edge confidence per researcher from the window's coauthor edges. */
  def degree(edges: Dataset[CoauthorEdgeRow]): DataFrame = {
    val e = edges.toDF()
    e.select(col("left").as("researcherId"), col("weight"), col("minIdentityConfidence"))
      .unionByName(
        e.select(col("right").as("researcherId"), col("weight"), col("minIdentityConfidence"))
      )
      .groupBy("researcherId")
      .agg(
        count(lit(1)).as("c"),
        sum("weight").as("w"),
        min("minIdentityConfidence").as("confEdge")
      )
  }

  /** Bridge measures per vertex index from the window's weighted edges and community labels.
    *
    * participation = 1 - sum over communities s of (k_s / k)^2, with k_s the summed weight of the
    * vertex's edges into community s (its own included) and k = W_r; cross = number of coauthors in
    * another community (edge count, not weight); connected = distinct other communities among the
    * coauthors.
    */
  def bridges(edgesIdx: DataFrame, communities: DataFrame): DataFrame = {
    val both = Algorithms.directed(edgesIdx)
    val comm = communities.select(col("id"), col("label"))
    val perComm = both
      .join(comm.select(col("id").as("dst"), col("label").as("cdst")), "dst")
      .groupBy("src", "cdst")
      .agg(sum("w").as("ks"), count(lit(1)).as("cnt"))
      .join(comm.select(col("id").as("src"), col("label").as("csrc")), "src")
    perComm
      .groupBy("src")
      .agg(
        sum("ks").as("k"),
        sum(col("ks") * col("ks")).as("ks2"),
        sum(when(col("cdst") =!= col("csrc"), col("cnt")).otherwise(lit(0L))).as("cross"),
        sum(when(col("cdst") =!= col("csrc"), lit(1L)).otherwise(lit(0L))).as("connected")
      )
      .select(
        col("src").as("id"),
        when(col("k") > 0, greatest(lit(0.0), lit(1.0) - col("ks2") / (col("k") * col("k"))))
          .as("participation"),
        col("cross"),
        col("connected")
      )
  }

  /** Coauthor edges of the window as (u, v, w) over vertex indexes, u < v. */
  def indexEdges(edges: Dataset[CoauthorEdgeRow], index: DataFrame): DataFrame =
    edges
      .toDF()
      .select("left", "right", "weight")
      .join(index.select(col("researcherId").as("left"), col("id").as("u")), "left")
      .join(index.select(col("researcherId").as("right"), col("id").as("v")), "right")
      .select(col("u"), col("v"), col("weight").as("w"))

  /** Splits the window facts into the exported metric tables. */
  def breadthRows(
      f: Dataset[ResearcherWindowFacts],
      window: YearWindow,
      ctx: ProvCtx
  ): Dataset[ResearcherMetricsRow] = {
    import f.sparkSession.implicits._
    f.map { r =>
      val values = Seq(
        MetricValueRow(CollaborationMetric.PublicationCount.wire, r.p.toDouble),
        MetricValueRow(CollaborationMetric.DistinctCoauthors.wire, r.c.toDouble),
        MetricValueRow(CollaborationMetric.TotalCoauthorOccurrences.wire, r.t.toDouble)
      ) ++ (if (r.p > 0)
              Seq(
                MetricValueRow(
                  CollaborationMetric.AverageCoauthorsPerPaper.wire,
                  r.t.toDouble / r.p.toDouble
                )
              )
            else Nil) ++ Seq(
        MetricValueRow(CollaborationMetric.MaxCoauthorsOnOnePaper.wire, r.m.toDouble),
        MetricValueRow(CollaborationMetric.WeightedDegree.wire, r.w)
      )
      metricsRow(r, window, values, ctx.row(JobMetrics, Seq(r.researcherId)))
    }
  }

  def groupSizeRows(
      f: Dataset[ResearcherWindowFacts],
      window: YearWindow,
      ctx: ProvCtx
  ): Dataset[ResearcherMetricsRow] = {
    import f.sparkSession.implicits._
    f.map { r =>
      val values = Seq(
        MetricValueRow(CollaborationMetric.ComponentSize.wire, r.componentSize.toDouble),
        MetricValueRow(CollaborationMetric.CommunitySize.wire, r.communitySize.toDouble)
      )
      metricsRow(r, window, values, ctx.row(JobGroups, Seq(r.researcherId)))
    }
  }

  def bridgeRows(
      f: Dataset[ResearcherWindowFacts],
      window: YearWindow,
      ctx: ProvCtx
  ): Dataset[ResearcherMetricsRow] = {
    import f.sparkSession.implicits._
    f.map { r =>
      val values = r.participation.toSeq.map(pc =>
        MetricValueRow(CollaborationMetric.ParticipationCoefficient.wire, pc)
      ) ++ Seq(
        MetricValueRow(CollaborationMetric.CrossCommunityDegree.wire, r.crossDegree.toDouble),
        MetricValueRow(
          CollaborationMetric.CommunitiesConnected.wire,
          r.communitiesConnected.toDouble
        )
      )
      metricsRow(r, window, values, ctx.row(JobBridges, Seq(r.researcherId)))
    }
  }

  def kcoreRows(
      f: Dataset[ResearcherWindowFacts],
      window: YearWindow,
      ctx: ProvCtx
  ): Dataset[ResearcherMetricsRow] = {
    import f.sparkSession.implicits._
    f.map { r =>
      val values = Seq(MetricValueRow(CollaborationMetric.Coreness.wire, r.coreness.toDouble))
      metricsRow(r, window, values, ctx.row(JobKcore, Seq(r.researcherId)))
    }
  }

  private def metricsRow(
      r: ResearcherWindowFacts,
      window: YearWindow,
      values: Seq[MetricValueRow],
      prov: ProvenanceRow
  ) =
    ResearcherMetricsRow(
      SchemaVersions.currentOf("ResearcherMetrics"),
      r.researcherId,
      window,
      values,
      r.identityConfidence,
      prov
    )

  def componentRows(
      f: Dataset[ResearcherWindowFacts],
      window: YearWindow,
      ctx: ProvCtx
  ): Dataset[GroupMembershipRow] = {
    import f.sparkSession.implicits._
    f.map(r =>
      GroupMembershipRow(
        SchemaVersions.currentOf("ResearcherGroupMembership"),
        r.researcherId,
        window,
        GroupKind.Component.wire,
        r.componentId,
        r.componentSize.toInt,
        ctx.row(JobGroups, Seq(r.researcherId))
      )
    )
  }

  def communityRows(
      f: Dataset[ResearcherWindowFacts],
      window: YearWindow,
      ctx: ProvCtx
  ): Dataset[GroupMembershipRow] = {
    import f.sparkSession.implicits._
    f.map(r =>
      GroupMembershipRow(
        SchemaVersions.currentOf("ResearcherGroupMembership"),
        r.researcherId,
        window,
        GroupKind.Community.wire,
        r.communityId,
        r.communitySize.toInt,
        ctx.row(JobGroups, Seq(r.researcherId))
      )
    )
  }

  /** The works behind M_r as a `metric` EvidenceItem (subjectIds = researcher, sorted works). */
  def metricEvidenceRows(
      f: Dataset[ResearcherWindowFacts],
      window: YearWindow,
      ctx: ProvCtx
  ): Dataset[MetricEvidenceRow] = {
    import f.sparkSession.implicits._
    f.map { r =>
      val works = r.mWorks.sortBy(_.workId)(CodePointOrder.ordering)
      val ids = works.map(_.workId)
      MetricEvidenceRow(
        r.researcherId,
        EvidenceItemRow.of(
          EvidenceType.Metric,
          r.researcherId +: ids,
          window,
          Some(MetricValueRow(CollaborationMetric.MaxCoauthorsOnOnePaper.wire, r.m.toDouble)),
          None,
          ctx.row(JobMetricEvidence, ids)
        ),
        works
      )
    }
  }
}
