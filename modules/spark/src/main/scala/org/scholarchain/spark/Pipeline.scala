package org.scholarchain.spark

import java.io.File

import scala.collection.mutable.{ArrayBuffer, LinkedHashMap}
import scala.reflect.runtime.universe.TypeTag

import org.apache.spark.sql.{DataFrame, Dataset, SparkSession}
import org.apache.spark.sql.functions._
import org.scholarchain.core._
import org.scholarchain.core.config.NamedWindow

/** Arguments of one pipeline run. Operational limits do not change outputs. */
final case class PipelineArgs(
    hadoopExport: File,
    identityAssignments: File,
    outRoot: File,
    config: SparkJobConfig,
    codeVersion: String,
    minFreeDiskBytes: Long,
    maxStageMillis: Long
)

final case class PipelineResult(runId: String, runDir: File, reused: Boolean, report: ujson.Value)

/** The Phase 6 collaboration-analysis pipeline (jobs 1-11 of the work order).
  *
  * Partitioning and shuffle boundaries (also written to the run report):
  *   - Inputs are JSON lines, split by file; decode is a narrow map. The decoded rows are staged as
  *     Parquet once (a materialization point) and re-read by every later job.
  *   - Shuffles happen at: assignment de-duplication (window by mentionId), the mention-work and
  *     mention-assignment joins (by mentionId / workId), the pair-to-researcher joins (by mention
  *     ID), per-pair aggregation (by left, right, workId), per-researcher aggregations, every
  *     iteration of the graph algorithms (joins by vertex index and group-bys), and the final hash
  *     repartition by table key before each write. `spark.sql.shuffle.partitions` sets the shuffle
  *     width.
  *   - Broadcast joins are disabled (spark.sql.autoBroadcastJoinThreshold = -1, set by the runner),
  *     so no edge table is ever broadcast; only bounded ID lists (path sources, top-10, sampled
  *     seeds) are collected to the driver.
  *   - Checkpoints (reliable, under the attempt root): authorship facts and pair works after the
  *     build, each window's coauthor edges and facts, and every iteration of components, label
  *     propagation, k-core and BFS, to cut lineage.
  */
object Pipeline {

  val StageProperty = "scholarchain.stage"

  final class Stages(
      spark: SparkSession,
      outRoot: File,
      minFreeDiskBytes: Long,
      maxStageMillis: Long
  ) {
    val timings: LinkedHashMap[String, Long] = LinkedHashMap.empty
    val disk: LinkedHashMap[String, Long] = LinkedHashMap.empty

    def apply[A](name: String)(body: => A): A = {
      val free = outRoot.getUsableSpace
      disk(name) = free
      if (free < minFreeDiskBytes)
        throw new OperationalLimitException(
          s"free disk ${free / (1L << 30)} GB below the floor before stage $name"
        )
      spark.sparkContext.setLocalProperty(StageProperty, name)
      spark.sparkContext.setJobDescription(name)
      val t0 = System.nanoTime()
      val r = body
      val ms = (System.nanoTime() - t0) / 1000000L
      timings(name) = ms
      println(s"[scholarchain] stage $name finished in ${ms / 1000.0} s")
      if (ms > maxStageMillis)
        throw new OperationalLimitException(s"stage $name took $ms ms (> $maxStageMillis ms)")
      r
    }
  }

  /** Input files (relative path, bytes, sha256) per input table, and the fingerprint. */
  def inputListing(args: PipelineArgs): (Seq[(String, String, Seq[FileEntry])], String) = {
    val hadoop = Inputs.HadoopTables.map { t =>
      val dir = new File(args.hadoopExport, t)
      ("hadoop", t, Files2.entries(args.hadoopExport, dir))
    }
    val er = Seq(
      (
        "entity-resolution",
        "identity_assignment",
        Files2.entries(args.identityAssignments, args.identityAssignments)
      )
    )
    val all = hadoop ++ er
    val lines = all.flatMap { case (src, t, fs) =>
      fs.map(f => s"$src\t$t\t${f.path}\t${f.bytes}\t${f.sha256}")
    }
    (all, "sha256:" + Hashing.sha256Hex(lines.mkString("\n")))
  }

  private def readJsonIfExists(f: File): Option[ujson.Value] =
    if (f.isFile) Some(ujson.read(Files2.readString(f))) else None

  def run(spark: SparkSession, args: PipelineArgs): PipelineResult = {
    val cfg = args.config
    SparkJobConfig.validate(cfg) match {
      case Nil => ()
      case e   => throw new IllegalArgumentException(s"invalid config: ${e.mkString("; ")}")
    }
    require(
      new File(args.hadoopExport, "work").isDirectory,
      s"no work table under ${args.hadoopExport}"
    )
    require(
      args.identityAssignments.isDirectory,
      s"no identity assignments at ${args.identityAssignments}"
    )
    val hadoopJob = readJsonIfExists(new File(args.hadoopExport, "_job.json")).getOrElse(
      throw new IllegalArgumentException(s"missing _job.json under ${args.hadoopExport}")
    )
    val erRunDir = args.identityAssignments.getAbsoluteFile.getParentFile.getParentFile
    val erManifest = readJsonIfExists(new File(erRunDir, "_manifest.json"))
    val snapshotId = hadoopJob("snapshotId").str

    val (listing, inputFingerprint) = inputListing(args)
    val runId = RunId
      .of(cfg.jobName, args.codeVersion, inputFingerprint, cfg.fingerprint)
      .fold(e => throw new IllegalArgumentException(e), identity)
    val hex = runId.value.stripPrefix(RunId.Prefix)
    val runDir = new File(args.outRoot, s"run=$hex")
    if (runDir.exists()) {
      Manifests.verifyExisting(runDir, runId.value)
      val report =
        readJsonIfExists(new File(runDir, Manifests.RunReportName)).getOrElse(ujson.Obj())
      println(s"[scholarchain] reusing verified output $runDir")
      return PipelineResult(runId.value, runDir, reused = true, report)
    }
    runDir.mkdirs()
    val checkpointDir = new File(args.outRoot, s"_checkpoint/run=$hex")
    val stagingDir = new File(args.outRoot, s"_staging/run=$hex")
    spark.sparkContext.setCheckpointDir(checkpointDir.getPath)

    val listener = new StageMetricsListener
    spark.sparkContext.addSparkListener(listener)
    try
      runCompute(
        spark,
        args,
        cfg,
        runId.value,
        runDir,
        stagingDir,
        snapshotId,
        hadoopJob,
        erManifest,
        listing,
        inputFingerprint,
        listener
      )
    finally spark.sparkContext.removeSparkListener(listener)
  }

  private def stage[R <: Product: TypeTag](
      spark: SparkSession,
      staging: File,
      name: String,
      decoded: Dataset[Decoded[R]]
  ): Dataset[Decoded[R]] = {
    import spark.implicits._
    val dir = new File(staging, name)
    decoded.write.mode("errorifexists").parquet(dir.getPath)
    spark.read.parquet(dir.getPath).as[Decoded[R]]
  }

  // scalastyle:off method.length
  private def runCompute(
      spark: SparkSession,
      args: PipelineArgs,
      cfg: SparkJobConfig,
      runId: String,
      runDir: File,
      staging: File,
      snapshotId: String,
      hadoopJob: ujson.Value,
      erManifest: Option[ujson.Value],
      listing: Seq[(String, String, Seq[FileEntry])],
      inputFingerprint: String,
      listener: StageMetricsListener
  ): PipelineResult = {
    import spark.implicits._
    val ctx = ProvCtx(runId, cfg.datasetId, snapshotId, cfg.policyVersion)
    val st = new Stages(spark, args.outRoot, args.minFreeDiskBytes, args.maxStageMillis)
    val out = new OutputWriter(spark, runDir, cfg.outputPartitions)
    val cp: Algorithms.Checkpoint = df => df.checkpoint(eager = true)
    val report = LinkedHashMap.empty[String, ujson.Value]
    val iterStats = ArrayBuffer.empty[IterStats]
    val all3 = Seq("s3")
    val neptune = Seq("neptune", "s3")
    val dynamo = Seq("dynamodb", "s3")

    // ---- Stage 1: decode and validate every input record ---------------------------------
    val (inputs, inputCounts, rejectCounts) = st("decode-inputs") {
      val h = args.hadoopExport
      val w = stage(
        spark,
        staging,
        "work",
        Inputs.decodeDir(spark, new File(h, "work"), Inputs.decodeWork)
      )
      val m = stage(
        spark,
        staging,
        "author_mention",
        Inputs.decodeDir(spark, new File(h, "author_mention"), Inputs.decodeMention)
      )
      val t = stage(
        spark,
        staging,
        "publication_team",
        Inputs.decodeDir(spark, new File(h, "publication_team"), Inputs.decodeTeam)
      )
      val p = stage(
        spark,
        staging,
        "coauthor_pair_candidate",
        Inputs.decodeDir(spark, new File(h, "coauthor_pair_candidate"), Inputs.decodePair)
      )
      val v = stage(
        spark,
        staging,
        "venue",
        Inputs.decodeDir(spark, new File(h, "venue"), Inputs.decodeVenue)
      )
      val i = stage(
        spark,
        staging,
        "institution",
        Inputs.decodeDir(spark, new File(h, "institution"), Inputs.decodeInstitution)
      )
      val l = stage(
        spark,
        staging,
        "external_id_link",
        Inputs.decodeDir(spark, new File(h, "external_id_link"), Inputs.decodeLink)
      )
      val a = stage(
        spark,
        staging,
        "identity_assignment",
        Inputs.decodeDir(spark, args.identityAssignments, Inputs.decodeAssignment)
      )
      val staged: Seq[(String, Dataset[_])] = Seq(
        "work" -> w,
        "author_mention" -> m,
        "publication_team" -> t,
        "coauthor_pair_candidate" -> p,
        "venue" -> v,
        "institution" -> i,
        "external_id_link" -> l,
        "identity_assignment" -> a
      )
      val lines = staged.map { case (n, d) => n -> d.count() }.toMap
      val rej = staged.map { case (n, d) =>
        n -> d.toDF().where(col("reject").isNotNull).count()
      }.toMap
      val rejects = staged.map { case (_, d) => Inputs.rejects(spark, d) }.reduce(_ unionByName _)
      out.write(
        rejects,
        "rejects",
        "rejects/v1",
        None,
        None,
        "(table, rawSha256)",
        all3,
        Seq("table", "rawSha256"),
        1
      )
      val tables = InputTables(
        Inputs.accepted(spark, w),
        Inputs.accepted(spark, m),
        Inputs.accepted(spark, t),
        Inputs.accepted(spark, p),
        Inputs.accepted(spark, v),
        Inputs.accepted(spark, i),
        Inputs.accepted(spark, l),
        Inputs.accepted(spark, a)
      )
      (tables, lines, rej)
    }
    report("inputLines") = ujson.Obj.from(inputCounts.toSeq.sortBy(_._1).map { case (k, n) =>
      k -> ujson.Num(n.toDouble)
    })
    report("rejects") = ujson.Obj.from(rejectCounts.toSeq.sortBy(_._1).map { case (k, n) =>
      k -> ujson.Num(n.toDouble)
    })

    // ---- Stage 2: canonical researchers, authorship, nodes, AUTHORED, coauthor pairs -------
    val (graph, authorship, pairWorks, researcherNodes, buildCounts) = st("canonical-graph") {
      val g = GraphBuild.build(spark, inputs)
      val auth = cp(g.authorship.toDF()).as[AuthorshipFact]
      val pw = cp(g.pairWorks.toDF()).as[PairWork]
      val aggC = cp(g.researcherAgg.toDF()).as[ResearcherAgg]
      val rNodes = GraphBuild.researcherNodes(aggC, ctx)
      out.write(
        rNodes,
        "nodes:researcher",
        "nodes/node_label=researcher",
        Some(CoreMirror.GraphNodeM),
        None,
        "nodeId",
        neptune,
        Seq("nodeId")
      )
      out.write(
        GraphBuild.workNodes(inputs.works, inputs.teams, ctx),
        "nodes:work",
        "nodes/node_label=work",
        Some(CoreMirror.GraphNodeM),
        None,
        "nodeId",
        neptune,
        Seq("nodeId")
      )
      out.write(
        GraphBuild.venueNodes(inputs.venues, ctx),
        "nodes:venue",
        "nodes/node_label=venue",
        Some(CoreMirror.GraphNodeM),
        None,
        "nodeId",
        neptune,
        Seq("nodeId"),
        1
      )
      out.write(
        GraphBuild.institutionNodes(inputs.institutions, ctx),
        "nodes:institution",
        "nodes/node_label=institution",
        Some(CoreMirror.GraphNodeM),
        None,
        "nodeId",
        neptune,
        Seq("nodeId"),
        1
      )
      out.write(
        spark.emptyDataset[GraphNodeRow],
        "nodes:topic",
        "nodes/node_label=topic",
        Some(CoreMirror.GraphNodeM),
        None,
        "nodeId",
        neptune,
        Seq("nodeId"),
        1
      )
      out.write(
        GraphBuild.researchers(aggC, ctx),
        "canonical_researcher",
        "canonical_researcher",
        Some(CoreMirror.ResearcherM),
        None,
        "researcherId",
        dynamo,
        Seq("researcherId")
      )
      out.write(
        GraphBuild.authorshipRows(auth, ctx),
        "authorship",
        "authorship",
        Some(CoreMirror.AuthorshipM),
        None,
        "mentionId",
        all3,
        Seq("mentionId")
      )
      out.write(
        GraphBuild.unresolvedMentions(inputs.mentions, inputs.assignments),
        "unresolved_mention",
        "unresolved_mention",
        Some(CoreMirror.MentionM),
        None,
        "mentionId",
        all3,
        Seq("mentionId")
      )
      out.write(
        GraphBuild.authoredEdges(auth, ctx),
        "edges:authored",
        "edges/edge_label=authored",
        Some(CoreMirror.GraphEdgeM),
        None,
        "edgeId",
        neptune,
        Seq("edgeId")
      )
      out.write(
        GraphBuild.publishedInEdges(inputs.works, inputs.venues, ctx),
        "edges:published_in",
        "edges/edge_label=published_in",
        Some(CoreMirror.GraphEdgeM),
        None,
        "edgeId",
        neptune,
        Seq("edgeId")
      )
      val counts = LinkedHashMap[String, ujson.Value](
        "researchers" -> aggC.count().toDouble,
        "conflictResearchers" -> aggC.where(col("conflict")).count().toDouble,
        "authorships" -> auth.count().toDouble,
        "conflictAuthorships" -> auth.where(col("conflict")).count().toDouble,
        "unresolvedMentions" -> inputs.mentions
          .join(inputs.assignments.select("mentionId"), Seq("mentionId"), "left_anti")
          .count()
          .toDouble,
        "works" -> inputs.works.count().toDouble,
        "venues" -> inputs.venues.count().toDouble,
        "institutions" -> inputs.institutions.count().toDouble,
        "externalIdLinks" -> inputs.links.count().toDouble,
        "pairPositions" -> inputs.pairs.count().toDouble,
        "erSelfPairsDropped" -> g.erSelfPairs.toDouble,
        "pairWorks" -> pw.count().toDouble
      )
      (g, auth, pw, rNodes, counts)
    }
    report("build") = ujson.Obj.from(buildCounts.toSeq)

    val index = st("vertex-index") {
      cp(Analytics.vertexIndex(spark, graph.researcherAgg.map(_.researcherId)))
    }
    val allResearchers = index.select("researcherId")

    // ---- Stage 3: per-window graph, metrics, components, communities, k-core, bridges -------
    val windowFacts = LinkedHashMap.empty[String, Dataset[ResearcherWindowFacts]]
    val windowEdges = LinkedHashMap.empty[String, (Dataset[CoauthorEdgeRow], DataFrame)]
    val windowReports = LinkedHashMap.empty[String, ujson.Value]
    val windowViolations = ArrayBuffer.empty[Dataset[IntegrityViolationRow]]
    for (nw <- cfg.windows) {
      val w = nw.window
      val label = nw.label
      st(s"window:$label") {
        val edges = cp(GraphBuild.coauthorEdges(pairWorks, w, cfg.graph.minEdgeWeight, ctx).toDF())
          .as[CoauthorEdgeRow]
        out.write(
          edges,
          s"coauthor_edge:$label",
          s"coauthor_edge/window_label=$label",
          Some(CoreMirror.CoauthorEdgeM),
          Some(label),
          "edgeId",
          dynamo,
          Seq("edgeId")
        )
        out.write(
          GraphBuild.coauthorGraphEdges(edges),
          s"edges:coauthored_with:$label",
          s"edges/edge_label=coauthored_with/window_label=$label",
          Some(CoreMirror.GraphEdgeM),
          Some(label),
          "edgeId",
          neptune,
          Seq("edgeId")
        )

        val b = Analytics.breadth(authorship, inputs.teams, nw)
        val active = b.select("researcherId")
        val endpoints = edges
          .toDF()
          .select(col("left").as("researcherId"), col("edgeId"))
          .unionByName(edges.toDF().select(col("right").as("researcherId"), col("edgeId")))
        windowViolations += endpoints
          .join(allResearchers, Seq("researcherId"), "left_anti")
          .select(
            lit("coauthor_edge_endpoint_missing").as("check"),
            lit(label).as("windowLabel"),
            col("researcherId").as("subjectId"),
            concat(lit("edge "), col("edgeId")).as("detail")
          )
          .as[IntegrityViolationRow]
        windowViolations += endpoints
          .join(active, Seq("researcherId"), "left_anti")
          .select(
            lit("coauthor_edge_endpoint_inactive").as("check"),
            lit(label).as("windowLabel"),
            col("researcherId").as("subjectId"),
            concat(lit("edge "), col("edgeId")).as("detail")
          )
          .as[IntegrityViolationRow]

        val vertices = active.join(index, "researcherId").select("id", "researcherId")
        val eIdx = cp(Analytics.indexEdges(edges, index))
        val (comp, ccStats) = Algorithms.connectedComponents(
          vertices,
          eIdx,
          cfg.algorithms.componentsMaxIterations,
          label,
          cp
        )
        val (comm, lpStats) = Algorithms.labelPropagation(
          vertices,
          eIdx,
          cfg.algorithms.labelPropagationMaxIterations,
          cfg.algorithms.labelPropagationSelfWeight,
          label,
          cp
        )
        val (core, kcStats) =
          Algorithms.coreness(vertices, eIdx, cfg.algorithms.kcoreMaxIterations, label, cp)
        iterStats ++= Seq(ccStats, lpStats, kcStats)
        val br = Analytics.bridges(eIdx, comm)
        val compSize = comp.groupBy("component").agg(count(lit(1)).as("componentSize"))
        val commSize = comm.groupBy("label").agg(count(lit(1)).as("communitySize"))
        val idName = index.select(col("id"), col("researcherId").as("rid"))
        val structure = vertices
          .join(comp, "id")
          .join(compSize, "component")
          .join(
            idName.withColumnRenamed("id", "component").withColumnRenamed("rid", "componentId"),
            "component"
          )
          .join(comm, "id")
          .join(commSize, "label")
          .join(
            idName.withColumnRenamed("id", "label").withColumnRenamed("rid", "communityId"),
            "label"
          )
          .join(core, "id")
          .join(br, Seq("id"), "left")
          .select(
            col("researcherId"),
            col("componentId"),
            col("componentSize"),
            col("communityId"),
            col("communitySize"),
            col("core").as("coreness"),
            col("participation"),
            coalesce(col("cross"), lit(0L)).as("crossDegree"),
            coalesce(col("connected"), lit(0L)).as("communitiesConnected")
          )
        val deg = Analytics.degree(edges)
        val facts = cp(
          b.join(deg, Seq("researcherId"), "left")
            .join(structure, "researcherId")
            .select(
              col("researcherId"),
              col("p"),
              col("t"),
              col("m"),
              coalesce(col("c"), lit(0L)).as("c"),
              coalesce(col("w"), lit(0.0)).as("w"),
              least(col("confOwn"), coalesce(col("confEdge"), col("confOwn")))
                .as("identityConfidence"),
              col("componentId"),
              col("componentSize"),
              col("communityId"),
              col("communitySize"),
              col("coreness"),
              col("participation"),
              col("crossDegree"),
              col("communitiesConnected"),
              col("mWorks")
            )
        ).as[ResearcherWindowFacts]
        windowFacts(label) = facts
        windowEdges(label) = (edges, eIdx)

        out.write(
          Analytics.breadthRows(facts, w, ctx),
          s"metrics:breadth:$label",
          s"metrics/metric_type=breadth/window_label=$label",
          Some(CoreMirror.MetricsM),
          Some(label),
          "(researcherId, window)",
          dynamo,
          Seq("researcherId")
        )
        out.write(
          Analytics.groupSizeRows(facts, w, ctx),
          s"metrics:group_size:$label",
          s"metrics/metric_type=group_size/window_label=$label",
          Some(CoreMirror.MetricsM),
          Some(label),
          "(researcherId, window)",
          dynamo,
          Seq("researcherId")
        )
        out.write(
          Analytics.bridgeRows(facts, w, ctx),
          s"bridges:$label",
          s"bridges/window_label=$label",
          Some(CoreMirror.MetricsM),
          Some(label),
          "(researcherId, window)",
          dynamo,
          Seq("researcherId")
        )
        out.write(
          Analytics.kcoreRows(facts, w, ctx),
          s"kcore:$label",
          s"kcore/window_label=$label",
          Some(CoreMirror.MetricsM),
          Some(label),
          "(researcherId, window)",
          dynamo,
          Seq("researcherId")
        )
        out.write(
          Analytics.componentRows(facts, w, ctx),
          s"components:$label",
          s"components/window_label=$label",
          Some(CoreMirror.MembershipM),
          Some(label),
          "(researcherId, window, groupKind)",
          dynamo,
          Seq("researcherId")
        )
        out.write(
          Analytics.communityRows(facts, w, ctx),
          s"communities:$label",
          s"communities/window_label=$label",
          Some(CoreMirror.MembershipM),
          Some(label),
          "(researcherId, window, groupKind)",
          dynamo,
          Seq("researcherId")
        )
        out.write(
          Analytics.metricEvidenceRows(facts, w, ctx),
          s"evidence:metric:$label",
          s"evidence/evidence_type=metric/window_label=$label",
          Some(CoreMirror.MetricEvidenceM),
          Some(label),
          "(evidenceId, window)",
          dynamo,
          Seq("evidence.evidenceId")
        )

        windowReports(label) =
          Reports.windowReport(facts.toDF(), edges.toDF(), nw, Seq(ccStats, lpStats, kcStats))
      }
    }
    report("windows") = ujson.Obj.from(windowReports.toSeq)

    // ---- Stage 4: path evidence and effective diameter (full window) ----------------------
    val full = cfg.fullWindow
    val pathsOutcome = st("paths-diameter") {
      val (edges, eIdx) = windowEdges(full.label)
      Paths.run(
        spark,
        windowFacts(full.label),
        index,
        edges,
        eIdx,
        full.window,
        full.label,
        cfg.algorithms,
        ctx,
        cp
      )
    }
    iterStats ++= pathsOutcome.stats
    out.write(
      pathsOutcome.paths.toDS(),
      s"path_evidence:${full.label}",
      s"path_evidence/window_label=${full.label}",
      Some(CoreMirror.PathEvidenceM),
      Some(full.label),
      "(evidenceId, window)",
      dynamo,
      Seq("evidence.evidenceId"),
      1
    )
    report("paths") = Reports.pathsReport(pathsOutcome)

    // ---- Stage 5: semantic summaries ---------------------------------------------------------
    st("semantic-summaries") {
      val prof = Summaries.profiles(
        spark,
        windowFacts(full.label),
        researcherNodes,
        authorship,
        inputs.works,
        inputs.venues,
        full.window,
        cfg.summaries.profileMinWorks,
        cfg.summaries.profileTopVenues,
        ctx
      )
      out.write(
        prof,
        s"semantic_summary:${Summaries.ProfileKind}:${full.label}",
        s"semantic_summary/summary_kind=${Summaries.ProfileKind}/window_label=${full.label}",
        Some(CoreMirror.SummaryM),
        Some(full.label),
        "(summaryKind, evidenceId, window)",
        Seq("s3vectors", "s3"),
        Seq("evidence.evidenceId")
      )
      for (nw <- cfg.windows) {
        val cs = Summaries.communities(
          spark,
          windowFacts(nw.label),
          researcherNodes,
          windowEdges(nw.label)._1,
          nw.window,
          cfg.summaries.communityMinMembers,
          cfg.summaries.communityTopMembers,
          ctx
        )
        out.write(
          cs,
          s"semantic_summary:${Summaries.CommunityKind}:${nw.label}",
          s"semantic_summary/summary_kind=${Summaries.CommunityKind}/window_label=${nw.label}",
          Some(CoreMirror.SummaryM),
          Some(nw.label),
          "(summaryKind, evidenceId, window)",
          Seq("s3vectors", "s3"),
          Seq("evidence.evidenceId")
        )
      }
      val pathIds = pathsOutcome.paths.flatMap(_.evidence.subjectIds).distinct
      val names: Map[String, String] =
        if (pathIds.isEmpty) Map.empty
        else
          researcherNodes
            .toDF()
            .where(col("nodeId").isin(pathIds: _*))
            .select("nodeId", "displayName")
            .collect()
            .map(r => r.getString(0) -> r.getString(1))
            .toMap
      val ps = Summaries.paths(pathsOutcome.paths, names, full.window, ctx)
      out.write(
        ps.toDS(),
        s"semantic_summary:${Summaries.PathKind}:${full.label}",
        s"semantic_summary/summary_kind=${Summaries.PathKind}/window_label=${full.label}",
        Some(CoreMirror.SummaryM),
        Some(full.label),
        "(summaryKind, evidenceId, window)",
        Seq("s3vectors", "s3"),
        Seq("evidence.evidenceId"),
        1
      )
      ()
    }

    // ---- Stage 6: integrity violations ------------------------------------------------------
    val integrity = st("integrity") {
      val v = (graph.violations +: windowViolations.toSeq).reduce(_ unionByName _)
      out.write(
        v,
        "integrity_violations",
        "integrity_violations/v1",
        None,
        None,
        "(check, windowLabel, subjectId, detail)",
        all3,
        Seq("check", "windowLabel", "subjectId", "detail"),
        1
      )
      new File(runDir, "integrity_violations/v1")
    }

    // ---- Stage 7: temporal change table ---------------------------------------------------
    val change = st("temporal-change") {
      Reports.changeTable(cfg, windowFacts.toMap, windowReports.toMap)
    }
    report("temporalChange") = change

    // ---- Stage 8: verify every exported table through the core contract -----------------
    val tables = st("verify-outputs")(out.verifyAll())
    val integrityCounts = spark.read
      .parquet(integrity.getPath)
      .groupBy("check", "windowLabel")
      .count()
      .orderBy("check", "windowLabel")
      .collect()
      .map(r => s"${r.getString(0)}|${r.getString(1)}" -> r.getLong(2))
    report("integrityViolations") = ujson.Obj.from(integrityCounts.toSeq.map { case (k, n) =>
      k -> ujson.Num(n.toDouble)
    })
    val summaryTotals = tables
      .filter(_.name.startsWith("semantic_summary:"))
      .groupBy(_.name.split(":")(1))
      .map { case (k, ts) => k -> ujson.Num(ts.map(_.records).sum.toDouble) }
    report("semanticSummaries") = ujson.Obj.from(summaryTotals.toSeq.sortBy(_._1))
    report("outputs") = ujson.Arr(tables.map { t =>
      ujson.Obj(
        "table" -> t.name,
        "path" -> t.relPath,
        "coreRecord" -> t.coreRecord.map(ujson.Str(_)).getOrElse(ujson.Null),
        "records" -> t.records.toDouble,
        "invalid" -> t.invalid.toDouble,
        "invalidSamples" -> ujson.Arr(t.invalidSamples.map(ujson.Str(_)): _*),
        "roundTripSampled" -> t.roundTripSampled,
        "roundTripFailures" -> t.roundTripFailures
      )
    }: _*)
    report("convergence") = ujson.Arr(iterStats.toSeq.map(Reports.iterJson): _*)

    // ---- Stage 9: manifests ---------------------------------------------------------------
    val inputsJson = ujson.Obj(
      "hadoopExport" -> ujson.Obj(
        "path" -> args.hadoopExport.getAbsolutePath,
        "runId" -> hadoopJob.obj.get("runId").getOrElse(ujson.Null),
        "jobName" -> hadoopJob.obj.get("jobName").getOrElse(ujson.Null),
        "snapshotId" -> snapshotId,
        "codeVersion" -> hadoopJob.obj.get("codeVersion").getOrElse(ujson.Null),
        "inputRuns" -> hadoopJob.obj.get("inputRuns").getOrElse(ujson.Arr())
      ),
      "identityAssignments" -> ujson.Obj(
        "path" -> args.identityAssignments.getAbsolutePath,
        "runId" -> erManifest.flatMap(_.obj.get("runId")).getOrElse(ujson.Null),
        "jobName" -> erManifest.flatMap(_.obj.get("jobName")).getOrElse(ujson.Null),
        "codeVersion" -> erManifest.flatMap(_.obj.get("codeVersion")).getOrElse(ujson.Null),
        "policyVersion" -> erManifest.flatMap(_.obj.get("policyVersion")).getOrElse(ujson.Null)
      ),
      "tables" -> ujson.Arr(listing.map { case (src, t, fs) =>
        ujson.Obj(
          "source" -> src,
          "table" -> t,
          "present" -> fs.nonEmpty,
          "files" -> ujson.Arr(fs.map(Manifests.fileJson): _*)
        )
      }: _*)
    )
    val manifest = ujson.Obj(
      "format" -> "scholarchain.spark.run-manifest",
      "formatVersion" -> 1,
      "jobName" -> cfg.jobName,
      "runId" -> runId,
      "codeVersion" -> args.codeVersion,
      "configFingerprint" -> cfg.fingerprint,
      "config" -> ujson.read(cfg.json),
      "inputFingerprint" -> inputFingerprint,
      "inputs" -> inputsJson,
      "outputs" -> ujson.Arr(tables.map(Manifests.tableJson): _*)
    )
    report("stageTimingsMs") = ujson.Obj.from(st.timings.toSeq.map { case (k, v) =>
      k -> ujson.Num(v.toDouble)
    })
    report("freeDiskBytesAtStageStart") = ujson.Obj.from(st.disk.toSeq.map { case (k, v) =>
      k -> ujson.Num(v.toDouble)
    })
    report("sparkStageMetrics") =
      ujson.Obj.from(listener.snapshot.toSeq.sortBy(_._1).map { case (g, m) =>
        g -> ujson.Obj.from(m.toSeq.sortBy(_._1).map { case (k, v) => k -> ujson.Num(v.toDouble) })
      })
    report("sparkConf") = ujson.Obj.from(
      Seq(
        "spark.master",
        "spark.sql.shuffle.partitions",
        "spark.default.parallelism",
        "spark.sql.autoBroadcastJoinThreshold",
        "spark.sql.adaptive.enabled"
      ).map { k =>
        k -> (spark.conf.getOption(k).map(ujson.Str(_)).getOrElse(ujson.Null): ujson.Value)
      }
    )
    report("partitioning") = Reports.partitioningNotes(cfg)
    val fullReport = ujson.Obj.from(
      Seq(
        "format" -> ujson.Str("scholarchain.spark.run-report"),
        "formatVersion" -> ujson.Num(1),
        "runId" -> ujson.Str(runId),
        "runDir" -> ujson.Str(runDir.getAbsolutePath),
        "emrRun" -> ujson.Str("deferred until after deployment (human decision 10)")
      ) ++ report.toSeq
    )
    // Writes the run report; refuses the run (no load manifests, no run manifest) when any table
    // failed core verification; otherwise writes the load manifests and, last, the run manifest.
    val written = Manifests.writeVerified(runDir, runId, tables, manifest, fullReport)
    PipelineResult(runId, runDir, reused = false, written)
  }
}

/** Plain counts and timings for the run report (no statistics engine). */
object Reports {

  def iterJson(s: IterStats): ujson.Value = ujson.Obj(
    "algorithm" -> s.algorithm,
    "windowLabel" -> s.windowLabel,
    "maxIterations" -> s.maxIterations,
    "iterations" -> s.iterations,
    "converged" -> s.converged,
    "changesPerIteration" -> ujson.Arr(s.changes.map(c => ujson.Num(c.toDouble)): _*)
  )

  /** count, min, exact P50/P90/P99, max and mean of one numeric column. */
  def distribution(df: DataFrame, c: org.apache.spark.sql.Column, name: String): ujson.Value = {
    val d = df.select(c.cast("double").as("x")).where(col("x").isNotNull)
    val r = d
      .agg(
        count(lit(1)),
        min("x"),
        expr("percentile(x, array(0.5, 0.9, 0.99))"),
        max("x"),
        avg("x")
      )
      .head()
    if (r.getLong(0) == 0) ujson.Obj("metric" -> name, "count" -> 0)
    else {
      val q = r.getSeq[Double](2)
      ujson.Obj(
        "metric" -> name,
        "count" -> r.getLong(0).toDouble,
        "min" -> r.getDouble(1),
        "p50" -> q(0),
        "p90" -> q(1),
        "p99" -> q(2),
        "max" -> r.getDouble(3),
        "mean" -> r.getDouble(4)
      )
    }
  }

  private val Buckets = Seq(
    (1L, 1L),
    (2L, 2L),
    (3L, 4L),
    (5L, 9L),
    (10L, 49L),
    (50L, 99L),
    (100L, 999L),
    (1000L, 9999L),
    (10000L, Long.MaxValue)
  )

  def sizeBuckets(sizes: DataFrame): ujson.Value = {
    val rows = sizes
      .select(col("size"))
      .groupBy("size")
      .count()
      .collect()
      .map(r => (r.getLong(0), r.getLong(1)))
    ujson.Obj.from(Buckets.map { case (lo, hi) =>
      val label = if (hi == Long.MaxValue) s"$lo+" else if (lo == hi) s"$lo" else s"$lo-$hi"
      label -> ujson.Num(rows.filter { case (s, _) => s >= lo && s <= hi }.map(_._2).sum.toDouble)
    })
  }

  def windowReport(
      facts: DataFrame,
      edges: DataFrame,
      nw: NamedWindow,
      stats: Seq[IterStats]
  ): ujson.Value = {
    val researchers = facts.count()
    val comps = facts.select(col("componentId"), col("componentSize").as("size")).distinct()
    val comms = facts.select(col("communityId"), col("communitySize").as("size")).distinct()
    val largest = comps.orderBy(col("size").desc, col("componentId")).limit(1).collect().headOption
    val largestSize = largest.map(_.getLong(1)).getOrElse(0L)
    val largestComm =
      comms.orderBy(col("size").desc, col("communityId")).limit(1).collect().headOption
    val coreDist = facts
      .groupBy("coreness")
      .count()
      .orderBy(col("coreness").desc)
      .limit(15)
      .collect()
      .map(r => r.getLong(0).toString -> ujson.Num(r.getLong(1).toDouble))
    ujson.Obj(
      "window" -> ujson.Obj(
        "startYear" -> nw.startYear,
        "endYear" -> nw.endYear,
        "years" -> (nw.endYear - nw.startYear + 1)
      ),
      "researchers" -> researchers.toDouble,
      "coauthorEdges" -> edges.count().toDouble,
      "edgeWeightSum" -> edges.agg(coalesce(sum("weight"), lit(0.0))).head().getDouble(0),
      "components" -> comps.count().toDouble,
      "largestComponentId" -> largest.map(r => ujson.Str(r.getString(0))).getOrElse(ujson.Null),
      "largestComponentSize" -> largestSize.toDouble,
      "largestComponentShare" -> (if (researchers > 0) largestSize.toDouble / researchers else 0.0),
      "singletons" -> facts.where(col("componentSize") === 1).count().toDouble,
      "componentSizeBuckets" -> sizeBuckets(comps),
      "communities" -> comms.count().toDouble,
      "largestCommunityId" -> largestComm.map(r => ujson.Str(r.getString(0))).getOrElse(ujson.Null),
      "largestCommunitySize" -> ujson.Num(largestComm.map(_.getLong(1).toDouble).getOrElse(0.0)),
      "communitySizeBuckets" -> sizeBuckets(comms),
      "corenessTop15Values" -> ujson.Obj.from(coreDist.toSeq),
      "researchersWithParticipationAboveZero" -> facts
        .where(col("participation") > 0)
        .count()
        .toDouble,
      "metricDistributions" -> ujson.Arr(
        distribution(facts, col("p"), "publication_count"),
        distribution(facts, col("c"), "distinct_coauthors"),
        distribution(facts, col("t"), "total_coauthor_occurrences"),
        distribution(facts, col("t") / col("p"), "average_coauthors_per_paper"),
        distribution(facts, col("m"), "max_coauthors_on_one_paper"),
        distribution(facts, col("w"), "weighted_degree"),
        distribution(facts, col("componentSize"), "component_size"),
        distribution(facts, col("communitySize"), "community_size"),
        distribution(facts, col("coreness"), "coreness"),
        distribution(facts, col("participation"), "participation_coefficient"),
        distribution(facts, col("crossDegree"), "cross_community_degree"),
        distribution(facts, col("communitiesConnected"), "communities_connected"),
        distribution(facts, col("identityConfidence"), "identity_confidence")
      ),
      "convergence" -> ujson.Arr(stats.map(iterJson): _*)
    )
  }

  def pathsReport(p: PathsOutcome): ujson.Value = ujson.Obj(
    "largestComponentId" -> p.largestComponentId.map(ujson.Str(_)).getOrElse(ujson.Null),
    "largestComponentSize" -> p.largestComponentSize.toDouble,
    "queries" -> p.queries.size,
    "found" -> p.queries.count(_.found),
    "notFoundSameComponent" -> p.queries.count(q => !q.found && q.sameComponent),
    "notFoundDifferentComponent" -> p.queries.count(q => !q.found && !q.sameComponent),
    "hopCountHistogram" -> ujson.Obj.from(
      p.queries
        .flatMap(_.hopCount)
        .groupBy(identity)
        .toSeq
        .sortBy(_._1)
        .map { case (h, xs) => h.toString -> ujson.Num(xs.size.toDouble) }
    ),
    "results" -> ujson.Arr(p.queries.map { q =>
      ujson.Obj(
        "pairKind" -> q.pairKind,
        "pairIndex" -> q.pairIndex,
        "source" -> q.sourceId,
        "target" -> q.targetId,
        "sameComponent" -> q.sameComponent,
        "found" -> q.found,
        "hops" -> q.hopCount.map(h => ujson.Num(h.toDouble)).getOrElse(ujson.Null)
      )
    }: _*),
    "effectiveDiameter" -> ujson.Obj(
      "method" -> ("one batched BFS from seeded sources in the largest component; hop-distance " +
        "distribution over reached (source, node) pairs; P90 is an ESTIMATE of the effective diameter"),
      "sources" -> p.diameter.sources,
      "maxDepth" -> p.diameter.maxDepth,
      "componentSize" -> p.diameter.componentSize.toDouble,
      "reachedPairs" -> p.diameter.reachedPairs.toDouble,
      "unreachedPairsWithinDepthCap" -> p.diameter.unreachedPairs.toDouble,
      "distribution" -> ujson.Obj.from(p.diameter.distribution.map { case (d, n) =>
        d.toString -> ujson.Num(n.toDouble)
      }),
      "p90Estimate" -> p.diameter.p90.map(x => ujson.Num(x.toDouble)).getOrElse(ujson.Null),
      "meanDistance" -> p.diameter.meanDistance.map(ujson.Num(_)).getOrElse(ujson.Null)
    )
  )

  /** Change table across consecutive non-full windows (plain counts). */
  def changeTable(
      cfg: SparkJobConfig,
      facts: Map[String, Dataset[ResearcherWindowFacts]],
      reports: Map[String, ujson.Value]
  ): ujson.Value = {
    val ws = cfg.windows.filterNot(_.label == cfg.fullWindowLabel)
    val years = ws.map(w => w.endYear - w.startYear + 1)
    val perWindow = ws.map { w =>
      val f = facts(w.label).toDF()
      val r = f
        .agg(
          count(lit(1)),
          avg("c"),
          avg(col("t") / col("p")),
          avg("m"),
          coalesce(sum(when(col("participation") > 0, 1L).otherwise(0L)), lit(0L))
        )
        .head()
      val rep = reports(w.label)
      def num(i: Int): ujson.Value = if (r.isNullAt(i)) ujson.Null else ujson.Num(r.getDouble(i))
      ujson.Obj(
        "window" -> w.label,
        "years" -> (w.endYear - w.startYear + 1),
        "researchers" -> r.getLong(0).toDouble,
        "meanDistinctCoauthors" -> num(1),
        "meanAverageCoauthorsPerPaper" -> num(2),
        "meanMaxCoauthorsOnOnePaper" -> num(3),
        "largestComponentShare" -> rep("largestComponentShare"),
        "communities" -> rep("communities"),
        "researchersWithParticipationAboveZero" -> r.getLong(4).toDouble
      )
    }
    val transitions = ws.zip(ws.drop(1)).map { case (a, b) =>
      val fa = facts(a.label).toDF()
      val fb = facts(b.label).toDF()
      val la = reports(a.label)("largestComponentId")
      val lb = reports(b.label)("largestComponentId")
      val laId = if (la.isNull) "" else la.str
      val lbId = if (lb.isNull) "" else lb.str
      val j = fa.as("a").join(fb.as("b"), "researcherId")
      def cnt(c: org.apache.spark.sql.Column) = coalesce(sum(when(c, 1L).otherwise(0L)), lit(0L))
      val r = j
        .agg(
          count(lit(1)),
          cnt(col("b.c") > col("a.c")),
          cnt(col("b.c") < col("a.c")),
          cnt(col("b.c") === col("a.c")),
          cnt(col("b.t") / col("b.p") > col("a.t") / col("a.p")),
          cnt(col("b.t") / col("b.p") < col("a.t") / col("a.p")),
          cnt(col("b.m") > col("a.m")),
          cnt(col("b.m") < col("a.m")),
          cnt(col("a.componentId") === laId && col("b.componentId") === lbId),
          cnt(col("a.componentId") =!= laId && col("b.componentId") === lbId),
          cnt(col("a.componentId") === laId && col("b.componentId") =!= lbId),
          cnt(col("a.communityId") === col("b.communityId")),
          cnt(
            coalesce(col("a.participation"), lit(0.0)) > 0 && coalesce(
              col("b.participation"),
              lit(0.0)
            ) > 0
          ),
          cnt(
            coalesce(col("a.participation"), lit(0.0)) === 0 && coalesce(
              col("b.participation"),
              lit(0.0)
            ) > 0
          ),
          cnt(
            coalesce(col("a.participation"), lit(0.0)) > 0 && coalesce(
              col("b.participation"),
              lit(0.0)
            ) === 0
          ),
          cnt(col("b.crossDegree") > col("a.crossDegree")),
          cnt(col("b.crossDegree") < col("a.crossDegree"))
        )
        .head()
      val names = Seq(
        "activeInBoth",
        "distinctCoauthorsUp",
        "distinctCoauthorsDown",
        "distinctCoauthorsSame",
        "avgCoauthorsPerPaperUp",
        "avgCoauthorsPerPaperDown",
        "maxCoauthorsOnOnePaperUp",
        "maxCoauthorsOnOnePaperDown",
        "inLargestComponentBoth",
        "joinedLargestComponent",
        "leftLargestComponent",
        "sameCommunityLabel",
        "participationAboveZeroBoth",
        "participationGained",
        "participationLost",
        "crossCommunityDegreeUp",
        "crossCommunityDegreeDown"
      )
      val onlyA = fa.count() - r.getLong(0)
      val onlyB = fb.count() - r.getLong(0)
      ujson.Obj.from(
        Seq(
          "from" -> ujson.Str(a.label),
          "to" -> ujson.Str(b.label),
          "activeOnlyInFrom" -> ujson.Num(onlyA.toDouble),
          "activeOnlyInTo" -> ujson.Num(onlyB.toDouble)
        ) ++
          names.zipWithIndex.map { case (n, i) => n -> ujson.Num(r.getLong(i).toDouble) }
      )
    }
    ujson.Obj(
      "limitations" -> ujson.Arr(
        s"Window lengths in years: ${ws.map(_.label).zip(years).map { case (l, y) => s"$l=$y" }.mkString(", ")}; " +
          "the last window is shorter, so counts in it are not directly comparable with earlier windows.",
        "All windows use the same inclusion rule (works whose dblp year is inside the window); a researcher " +
          "is in a window only if they have a work in it.",
        "Community labels are not aligned across windows; sameCommunityLabel only counts identical label IDs.",
        "The input is the Phase 5 bounded subset (16 venues), so cross-window changes also reflect venue growth."
      ),
      "perWindow" -> ujson.Arr(perWindow: _*),
      "transitions" -> ujson.Arr(transitions: _*)
    )
  }

  def partitioningNotes(cfg: SparkJobConfig): ujson.Value = ujson.Obj(
    "inputs" -> "JSON lines split by file; decode is a narrow map; decoded rows staged once as Parquet.",
    "shuffleBoundaries" -> ujson.Arr(
      "assignment de-duplication: window partitioned by mentionId",
      "mention-work and mention-assignment joins (sort-merge on workId, mentionId)",
      "pair-to-researcher joins on left and right mention IDs, then group by (left, right, workId)",
      "per-window coauthor edge aggregation: group by (left, right)",
      "per-researcher aggregations (P, T, M, C, W, identity)",
      "every iteration of components, label propagation, k-core and BFS (joins on vertex index, group-bys)",
      "hash repartition by table key before each write (deterministic file contents)"
    ),
    "broadcast" -> "autoBroadcastJoinThreshold = -1: no automatic broadcast; only bounded ID lists reach the driver",
    "caching" -> "no in-memory caching; reliable checkpoints are used instead (deterministic lineage cuts)",
    "checkpoints" -> ujson.Arr(
      "authorship facts, pair works, researcher aggregates and the vertex index after the build",
      "each window's coauthor edges, indexed edges and researcher facts",
      "every iteration of components, label propagation, k-core and BFS"
    ),
    "outputPartitions" -> cfg.outputPartitions
  )
}
