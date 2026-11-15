package org.scholarchain.spark

import org.apache.spark.sql.{DataFrame, Dataset, SparkSession}
import org.apache.spark.sql.functions._
import org.scholarchain.core._

/** Decoded inputs of one run. */
final case class InputTables(
    works: Dataset[WorkIn],
    mentions: Dataset[AuthorMentionRow],
    teams: Dataset[TeamIn],
    pairs: Dataset[PairIn],
    venues: Dataset[VenueIn],
    institutions: Dataset[InstitutionIn],
    links: Dataset[LinkIn],
    assignments: Dataset[AssignmentIn]
)

/** Per-researcher identity facts gathered from its authorships. */
final case class ResearcherAgg(
    researcherId: String,
    anchorYear: Int,
    anchorWorkId: String,
    anchorPosition: Int,
    anchorMentionId: String,
    names: Seq[String],
    nameCounts: Seq[NameCount],
    orcids: Seq[OrcidAssertion],
    minConfidence: Double,
    conflict: Boolean,
    reasonCodes: Seq[String],
    mentionCount: Long,
    workCount: Long
) {
  def orcidList: Seq[OrcidAssertion] = Option(orcids).getOrElse(Nil)
  def reasonList: Seq[String] = Option(reasonCodes).getOrElse(Nil)
}
final case class NameCount(negCount: Long, name: String)
final case class OrcidAssertion(
    orcid: String,
    dblpKey: String,
    datasetId: String,
    snapshotId: String
)

/** Internal authorship row used by the analytics (one per mention). */
final case class AuthorshipFact(
    researcherId: String,
    workId: String,
    mentionId: String,
    position: Int,
    year: Int,
    confidence: Double,
    conflict: Boolean,
    name: String,
    orcid: Option[String],
    dblpKey: String,
    mentionDatasetId: String,
    mentionSnapshotId: String
)

/** One coauthor pair on one work after mapping to researchers (left < right). */
final case class PairWork(
    left: String,
    right: String,
    workId: String,
    year: Int,
    confidence: Double
)

final case class BuiltGraph(
    authorship: Dataset[AuthorshipFact],
    pairWorks: Dataset[PairWork],
    researcherAgg: Dataset[ResearcherAgg],
    violations: Dataset[IntegrityViolationRow],
    erSelfPairs: Long
)

/** Job 1 (canonical researchers and authorship) and job 2 (coauthor pairs), plus integrity checks.
  */
object GraphBuild {

  val JobCanonical = "spark.canonical-researchers"
  val JobAuthorship = "spark.authorship"
  val JobNodes = "spark.graph-nodes"
  val JobEdges = "spark.graph-edges"
  val JobCoauthor = "spark.coauthor-edges"

  private type Column = org.apache.spark.sql.Column

  private def v(df: DataFrame, check: String, subject: Column, detail: Column): DataFrame =
    df.select(
      lit(check).as("check"),
      lit("").as("windowLabel"),
      subject.cast("string").as("subjectId"),
      detail.cast("string").as("detail")
    )

  def build(spark: SparkSession, in: InputTables): BuiltGraph = {
    import spark.implicits._

    // Identity assignments: exactly one per mention is expected; duplicates are violations and
    // the smallest researcher ID is kept so the choice is deterministic.
    val asgRanked = in.assignments
      .toDF()
      .withColumn(
        "rn",
        row_number().over(
          org.apache.spark.sql.expressions.Window
            .partitionBy("mentionId")
            .orderBy(col("researcherId"), col("outcome"), col("confidence"))
        )
      )
    val asg = asgRanked.where(col("rn") === 1).drop("rn")
    val dupAsg = v(
      asgRanked.where(col("rn") > 1),
      "duplicate_identity_assignment",
      col("mentionId"),
      concat(lit("extra assignment to "), col("researcherId"))
    )

    val works = in.works.toDF()
    val mentions = in.mentions.toDF()

    val mentionWork =
      mentions.join(works.select("workId", "year", "dblpKey"), Seq("workId"), "left")
    val mentionWorkMissing = v(
      mentionWork.where(col("year").isNull),
      "mention_work_missing",
      col("mentionId"),
      concat(lit("work "), col("workId"), lit(" not in work input"))
    )
    val assignmentMentionMissing = v(
      asg.join(mentions.select("mentionId"), Seq("mentionId"), "left_anti"),
      "assignment_mention_missing",
      col("mentionId"),
      concat(lit("assigned to "), col("researcherId"), lit(" but not in author_mention"))
    )

    val authorship = mentionWork
      .where(col("year").isNotNull)
      .join(asg, Seq("mentionId"))
      .select(
        col("researcherId"),
        col("workId"),
        col("mentionId"),
        col("position"),
        col("year"),
        col("confidence"),
        (col("outcome") === MatchOutcome.Conflict.wire).as("conflict"),
        col("name"),
        col("orcid"),
        col("dblpKey"),
        col("provenance.datasetId").as("mentionDatasetId"),
        col("provenance.snapshotId").as("mentionSnapshotId")
      )
      .as[AuthorshipFact]

    // Team size consistency: dblp teamSize versus mentions per work.
    val mentionsPerWork = mentions.groupBy("workId").agg(count(lit(1)).as("mentions"))
    val teamMismatch = v(
      in.teams
        .toDF()
        .join(mentionsPerWork, Seq("workId"), "left")
        .where(coalesce(col("mentions"), lit(0L)) =!= col("teamSize")),
      "team_size_mismatch",
      col("workId"),
      concat(
        lit("teamSize "),
        col("teamSize"),
        lit(" vs mentions "),
        coalesce(col("mentions"), lit(0L))
      )
    )
    val workVenueMissing = v(
      works
        .where(col("venueKey").isNotNull)
        .join(in.venues.toDF().select("venueKey"), Seq("venueKey"), "left_anti"),
      "work_venue_missing",
      col("workId"),
      concat(lit("venue "), col("venueKey"), lit(" not in venue input"))
    )

    // Researcher identity aggregation.
    val nameCounts = authorship
      .groupBy("researcherId", "name")
      .agg(count(lit(1)).as("n"))
      .groupBy("researcherId")
      .agg(
        sort_array(collect_list(struct(negateL(col("n")).as("negCount"), col("name"))))
          .as("nameCounts"),
        sort_array(collect_list(col("name"))).as("names")
      )
    val orcids = authorship
      .where(col("orcid").isNotNull)
      .groupBy("researcherId", "orcid")
      .agg(min(struct(col("dblpKey"), col("mentionDatasetId"), col("mentionSnapshotId"))).as("m"))
      .groupBy("researcherId")
      .agg(
        sort_array(
          collect_list(
            struct(
              col("orcid"),
              col("m.dblpKey").as("dblpKey"),
              col("m.mentionDatasetId").as("datasetId"),
              col("m.mentionSnapshotId").as("snapshotId")
            )
          )
        ).as("orcids")
      )
    val core = authorship
      .groupBy("researcherId")
      .agg(
        min(struct(col("year"), col("workId"), col("position"), col("mentionId"))).as("anchor"),
        min("confidence").as("minConfidence"),
        max(col("conflict")).as("conflict"),
        count(lit(1)).as("mentionCount"),
        countDistinct("workId").as("workCount")
      )
    val reasons = authorship
      .join(asg.select("mentionId", "reasonCodes"), Seq("mentionId"))
      .select(col("researcherId"), explode(col("reasonCodes")).as("code"))
      .groupBy("researcherId")
      .agg(sort_array(collect_set("code")).as("reasonCodes"))
    val researcherAgg = core
      .join(nameCounts, Seq("researcherId"))
      .join(orcids, Seq("researcherId"), "left")
      .join(reasons, Seq("researcherId"), "left")
      .select(
        col("researcherId"),
        col("anchor.year").as("anchorYear"),
        col("anchor.workId").as("anchorWorkId"),
        col("anchor.position").as("anchorPosition"),
        col("anchor.mentionId").as("anchorMentionId"),
        array_distinct(col("names")).as("names"),
        col("nameCounts"),
        col("orcids"),
        col("minConfidence"),
        col("conflict"),
        col("reasonCodes"),
        col("mentionCount"),
        col("workCount")
      )
      .as[ResearcherAgg]

    val anchorMismatch = researcherAgg
      .filter(r =>
        ResearcherId.fromAnchorMention(MentionId(r.anchorMentionId)).value != r.researcherId
      )
      .toDF()
    val anchorViolations = v(
      anchorMismatch,
      "researcher_anchor_mismatch",
      col("researcherId"),
      concat(lit("earliest mention "), col("anchorMentionId"), lit(" does not derive the ID"))
    )

    // Coauthor pairs: each supporting position pair maps to two researchers.
    val pairs = in.pairs.toDF()
    val asgL = asg.select(
      col("mentionId").as("leftMentionId"),
      col("researcherId").as("ra"),
      col("confidence").as("ca")
    )
    val asgR = asg.select(
      col("mentionId").as("rightMentionId"),
      col("researcherId").as("rb"),
      col("confidence").as("cb")
    )
    val mapped = pairs
      .join(asgL, Seq("leftMentionId"), "left")
      .join(asgR, Seq("rightMentionId"), "left")
      .join(works.select(col("workId"), col("year").as("workYear")), Seq("workId"), "left")
    val pairUnassigned = v(
      mapped.where(col("ra").isNull || col("rb").isNull),
      "pair_mention_unassigned",
      col("workId"),
      concat(
        lit("pair "),
        col("leftMentionId"),
        lit("/"),
        col("rightMentionId"),
        lit(" has an endpoint without identity assignment")
      )
    )
    val pairWorkMissing = v(
      mapped.where(col("workYear").isNull),
      "pair_work_missing",
      col("workId"),
      lit("pair work not in work input")
    )
    val pairYearMismatch = v(
      mapped.where(col("workYear").isNotNull && col("workYear") =!= col("year")),
      "pair_year_mismatch",
      col("workId"),
      concat(lit("pair year "), col("year"), lit(" vs work year "), col("workYear"))
    )
    val resolved =
      mapped.where(col("ra").isNotNull && col("rb").isNotNull && col("workYear").isNotNull)
    val selfPairs = resolved.where(col("ra") === col("rb")).count()
    val pairWorks = resolved
      .where(col("ra") =!= col("rb"))
      .select(
        least(col("ra"), col("rb")).as("left"),
        greatest(col("ra"), col("rb")).as("right"),
        col("workId"),
        col("workYear").as("year"),
        least(col("ca"), col("cb")).as("confidence")
      )
      .groupBy("left", "right", "workId")
      .agg(min("year").as("year"), min("confidence").as("confidence"))
      .as[PairWork]

    // Cross-check: pairs derived from Hadoop pair candidates versus researchers sharing a work.
    val rw = authorship.select("researcherId", "workId").distinct()
    val teamPairs = rw
      .as("a")
      .join(
        rw.as("b"),
        col("a.workId") === col("b.workId") && col("a.researcherId") < col("b.researcherId")
      )
      .select(
        col("a.researcherId").as("left"),
        col("b.researcherId").as("right"),
        col("a.workId").as("workId")
      )
    val pairKeys = pairWorks.select("left", "right", "workId")
    val pairNotTeam = v(
      pairKeys.exceptAll(teamPairs),
      "pair_not_in_team",
      concat(col("left"), lit("|"), col("right")),
      concat(lit("work "), col("workId"))
    )
    val teamNotPair = v(
      teamPairs.exceptAll(pairKeys),
      "team_pair_without_candidate",
      concat(col("left"), lit("|"), col("right")),
      concat(lit("work "), col("workId"))
    )

    val violations = Seq(
      dupAsg,
      mentionWorkMissing,
      assignmentMentionMissing,
      teamMismatch,
      workVenueMissing,
      anchorViolations,
      pairUnassigned,
      pairWorkMissing,
      pairYearMismatch,
      pairNotTeam,
      teamNotPair
    ).reduce(_ unionByName _).as[IntegrityViolationRow]

    BuiltGraph(authorship, pairWorks, researcherAgg, violations, selfPairs)
  }

  private def negateL(c: org.apache.spark.sql.Column) = lit(0L) - c

  /** CanonicalResearcher rows: dblpNames sorted by code point; displayName is the most frequent
    * name (ties: smallest by code point); one dblp ORCID iD only when the cluster has exactly one
    * distinct value (several values are an identity conflict and are reported on the node).
    */
  def researchers(agg: Dataset[ResearcherAgg], ctx: ProvCtx): Dataset[CanonicalResearcherRow] = {
    import agg.sparkSession.implicits._
    agg.map { r =>
      val ext =
        if (r.orcidList.size == 1) {
          val o = r.orcidList.head
          Seq(SourcedExternalIdRow("orcid", o.orcid, "dblp", o.datasetId, o.snapshotId, o.dblpKey))
        } else Nil
      CanonicalResearcherRow(
        SchemaVersions.currentOf("CanonicalResearcher"),
        r.researcherId,
        r.anchorMentionId,
        r.nameCounts.head.name,
        r.names.distinct.sorted(CodePointOrder.ordering),
        ext,
        ctx.row(JobCanonical, Seq(r.anchorMentionId))
      )
    }
  }

  def researcherNodes(agg: Dataset[ResearcherAgg], ctx: ProvCtx): Dataset[GraphNodeRow] = {
    import agg.sparkSession.implicits._
    agg.map { r =>
      val props = Map(
        "anchorMentionId" -> r.anchorMentionId,
        "identityOutcome" -> (if (r.conflict) "conflict" else "accepted"),
        "minIdentityConfidence" -> r.minConfidence.toString,
        "mentionCount" -> r.mentionCount.toString,
        "workCount" -> r.workCount.toString,
        "reasonCodes" -> r.reasonList.mkString(","),
        "distinctDblpOrcids" -> r.orcidList.size.toString
      )
      GraphNodeRow(
        SchemaVersions.currentOf("GraphNode"),
        r.researcherId,
        GraphNodeLabel.Researcher.wire,
        r.nameCounts.head.name,
        props,
        ctx.row(JobNodes, Seq(r.anchorMentionId))
      )
    }
  }

  def workNodes(
      works: Dataset[WorkIn],
      teams: Dataset[TeamIn],
      ctx: ProvCtx
  ): Dataset[GraphNodeRow] = {
    import works.sparkSession.implicits._
    works
      .joinWith(teams, works("workId") === teams("workId"), "left")
      .map { case (w, t) =>
        val props = Map(
          "dblpKey" -> w.dblpKey,
          "year" -> w.year.toString,
          "publicationType" -> w.publicationType
        ) ++ w.venueKey.map("venueKey" -> _) ++ w.doi.map("doi" -> _) ++
          Option(t).map(x => "teamSize" -> x.teamSize.toString)
        GraphNodeRow(
          SchemaVersions.currentOf("GraphNode"),
          w.workId,
          GraphNodeLabel.Work.wire,
          w.title,
          props,
          ctx.row(JobNodes, Seq(w.dblpKey), Some(ContentHash.of(w.title)))
        )
      }
  }

  def venueNodes(venues: Dataset[VenueIn], ctx: ProvCtx): Dataset[GraphNodeRow] = {
    import venues.sparkSession.implicits._
    venues.map(v =>
      GraphNodeRow(
        SchemaVersions.currentOf("GraphNode"),
        v.venueKey,
        GraphNodeLabel.Venue.wire,
        v.name,
        Map.empty,
        ctx.row(JobNodes, Seq(v.venueKey))
      )
    )
  }

  def institutionNodes(insts: Dataset[InstitutionIn], ctx: ProvCtx): Dataset[GraphNodeRow] = {
    import insts.sparkSession.implicits._
    insts.map(i =>
      GraphNodeRow(
        SchemaVersions.currentOf("GraphNode"),
        i.institutionId,
        GraphNodeLabel.Institution.wire,
        i.name,
        i.countryCode.map("countryCode" -> _).toMap,
        ctx.row(JobNodes, Seq(i.institutionId))
      )
    )
  }

  def authorshipRows(a: Dataset[AuthorshipFact], ctx: ProvCtx): Dataset[AuthorshipRow] = {
    import a.sparkSession.implicits._
    a.map(f =>
      AuthorshipRow(
        SchemaVersions.currentOf("AuthorshipRecord"),
        f.researcherId,
        f.workId,
        f.mentionId,
        f.position,
        f.year,
        f.confidence,
        ctx.row(JobAuthorship, Seq(f.dblpKey, f.mentionId))
      )
    )
  }

  /** Mentions without an identity assignment, kept as they are (never merged). */
  def unresolvedMentions(
      mentions: Dataset[AuthorMentionRow],
      assignments: Dataset[AssignmentIn]
  ): Dataset[AuthorMentionRow] = {
    import mentions.sparkSession.implicits._
    mentions
      .join(assignments.select("mentionId"), Seq("mentionId"), "left_anti")
      .as[AuthorMentionRow]
  }

  /** AUTHORED edges, one per (researcher, work) (two mentions of one researcher on one work give
    * one edge); window = the work's publication year.
    */
  def authoredEdges(a: Dataset[AuthorshipFact], ctx: ProvCtx): Dataset[GraphEdgeRow] = {
    import a.sparkSession.implicits._
    a.groupBy("researcherId", "workId")
      .agg(min("year").as("year"), sort_array(collect_list("mentionId")).as("mentionIds"))
      .as[(String, String, Int, Seq[String])]
      .map { case (r, w, y, ms) =>
        val win = YearWindow(y, y)
        GraphEdgeRow(
          SchemaVersions.currentOf("GraphEdge"),
          EdgeId
            .of(GraphEdgeLabel.Authored, r, w, win)
            .fold(e => throw new IllegalStateException(e), _.value),
          GraphEdgeLabel.Authored.wire,
          r,
          w,
          win,
          None,
          ctx.row(JobEdges, ms)
        )
      }
  }

  /** PUBLISHED_IN edges for works whose venue exists; window = the publication year. */
  def publishedInEdges(
      works: Dataset[WorkIn],
      venues: Dataset[VenueIn],
      ctx: ProvCtx
  ): Dataset[GraphEdgeRow] = {
    import works.sparkSession.implicits._
    works
      .where(col("venueKey").isNotNull)
      .join(venues.select("venueKey"), Seq("venueKey"))
      .select("workId", "venueKey", "year", "dblpKey")
      .as[(String, String, Int, String)]
      .map { case (w, vk, y, key) =>
        val win = YearWindow(y, y)
        GraphEdgeRow(
          SchemaVersions.currentOf("GraphEdge"),
          EdgeId
            .of(GraphEdgeLabel.PublishedIn, w, vk, win)
            .fold(e => throw new IllegalStateException(e), _.value),
          GraphEdgeLabel.PublishedIn.wire,
          w,
          vk,
          win,
          None,
          ctx.row(JobEdges, Seq(key))
        )
      }
  }

  /** Coauthor edges of one window: one per canonical pair, weight = joint publication count
    * (distinct works), first/last year, all supporting work IDs sorted, minimum identity
    * confidence. Pairs below `minEdgeWeight` are left out.
    */
  def coauthorEdges(
      pairWorks: Dataset[PairWork],
      window: YearWindow,
      minEdgeWeight: Double,
      ctx: ProvCtx
  ): Dataset[CoauthorEdgeRow] = {
    import pairWorks.sparkSession.implicits._
    pairWorks
      .where(col("year").between(window.startYear, window.endYear))
      .groupBy("left", "right")
      .agg(
        count(lit(1)).as("n"),
        min("year").as("firstYear"),
        max("year").as("lastYear"),
        sort_array(collect_list("workId")).as("works"),
        min("confidence").as("confidence")
      )
      .where(col("n").cast("double") >= minEdgeWeight)
      .select("left", "right", "n", "firstYear", "lastYear", "works", "confidence")
      .as[(String, String, Long, Int, Int, Seq[String], Double)]
      .map { case (l, r, n, fy, ly, ws, conf) =>
        CoauthorEdgeRow(
          SchemaVersions.currentOf("CoauthorEdge"),
          EdgeId
            .of(GraphEdgeLabel.CoauthoredWith, l, r, window)
            .fold(e => throw new IllegalStateException(e), _.value),
          l,
          r,
          window,
          n.toDouble,
          n.toInt,
          fy,
          ly,
          ws,
          conf,
          ctx.row(JobCoauthor, ws)
        )
      }
  }

  /** The coauthored_with GraphEdge of a CoauthorEdge (same edge ID, weight =
    * jointPublicationCount).
    */
  def coauthorGraphEdges(edges: Dataset[CoauthorEdgeRow]): Dataset[GraphEdgeRow] = {
    import edges.sparkSession.implicits._
    edges.map(e =>
      GraphEdgeRow(
        SchemaVersions.currentOf("GraphEdge"),
        e.edgeId,
        GraphEdgeLabel.CoauthoredWith.wire,
        e.left,
        e.right,
        e.window,
        Some(e.weight),
        e.provenance.copy(jobName = JobEdges)
      )
    )
  }
}
