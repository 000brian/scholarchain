package org.scholarchain.spark

import org.apache.spark.sql.{DataFrame, Dataset, SparkSession}
import org.apache.spark.sql.functions._
import org.scholarchain.core._

/** One requested path query and its outcome (bounded; reported in the run report). */
final case class PathQueryResult(
    pairKind: String,
    pairIndex: Int,
    sourceId: String,
    targetId: String,
    sameComponent: Boolean,
    found: Boolean,
    hopCount: Option[Int]
)

/** Effective-diameter estimate from one batched BFS over sampled sources. */
final case class DiameterEstimate(
    sources: Int,
    maxDepth: Int,
    componentSize: Long,
    reachedPairs: Long,
    unreachedPairs: Long,
    distribution: Seq[(Int, Long)],
    p90: Option[Int],
    meanDistance: Option[Double]
)

final case class PathsOutcome(
    paths: Seq[PathEvidenceRow],
    queries: Seq[PathQueryResult],
    diameter: DiameterEstimate,
    largestComponentId: Option[String],
    largestComponentSize: Long,
    stats: Seq[IterStats]
)

/** Bounded path evidence and the effective-diameter estimate (full window only). Only bounded
  * results reach the driver: at most `pathTopK` top researchers, `2 * pathRandomPairs` and
  * `diameterSources` sampled IDs, the reconstructed paths, and the distance histogram.
  */
object Paths {

  val JobPaths = "spark.path-evidence"
  val TopKind = "top-distinct-coauthors"
  val RandomKind = "seeded-random-largest-component"

  /** IDs ordered by sha256(seed | salt | researcherId), the deterministic "random" order. */
  def seeded(ids: DataFrame, seed: String, salt: String, n: Int): Seq[String] =
    if (n <= 0) Nil
    else
      ids
        .select(
          col("researcherId"),
          sha2(concat(lit(s"$seed|$salt|"), col("researcherId")), 256).as("h")
        )
        .orderBy(col("h"), col("researcherId"))
        .limit(n)
        .collect()
        .map(_.getString(0))
        .toSeq

  def run(
      spark: SparkSession,
      facts: Dataset[ResearcherWindowFacts],
      index: DataFrame,
      edges: Dataset[CoauthorEdgeRow],
      edgesIdx: DataFrame,
      window: YearWindow,
      windowLabel: String,
      cfg: AlgorithmSettings,
      ctx: ProvCtx,
      cp: Algorithms.Checkpoint
  ): PathsOutcome = {
    import spark.implicits._
    val f = facts.toDF()

    val top: Seq[String] =
      if (cfg.pathTopK <= 0) Nil
      else
        f.orderBy(col("c").desc, col("researcherId"))
          .limit(cfg.pathTopK)
          .select("researcherId")
          .collect()
          .map(_.getString(0))
          .toSeq
    val largest = f
      .select("componentId", "componentSize")
      .distinct()
      .orderBy(col("componentSize").desc, col("componentId"))
      .limit(1)
      .collect()
      .headOption
      .map(r => (r.getString(0), r.getLong(1)))
    val largestMembers = largest match {
      case Some((id, _)) => f.where(col("componentId") === id).select("researcherId")
      case None          => f.select("researcherId").limit(0)
    }
    val pairSeeds = seeded(largestMembers, cfg.seed, "path-pairs", 2 * cfg.pathRandomPairs)
    val diameterSeeds = seeded(largestMembers, cfg.seed, "diameter", cfg.diameterSources)

    val topPairs = for {
      i <- top.indices
      j <- top.indices if i < j
    } yield (TopKind, top(i), top(j))
    val randomPairs = pairSeeds.grouped(2).collect { case Seq(a, b) => (RandomKind, a, b) }.toSeq
    val pairs: Seq[(String, Int, String, String)] =
      (topPairs ++ randomPairs).zipWithIndex.map { case ((k, a, b), i) => (k, i, a, b) }

    val wanted = (pairs.flatMap(p => Seq(p._3, p._4)) ++ diameterSeeds).distinct
    val idOf: Map[String, Long] =
      if (wanted.isEmpty) Map.empty
      else
        index
          .where(col("researcherId").isin(wanted: _*))
          .select("researcherId", "id")
          .collect()
          .map(r => r.getString(0) -> r.getLong(1))
          .toMap
    val compOf: Map[String, String] =
      if (wanted.isEmpty) Map.empty
      else
        f.where(col("researcherId").isin(wanted: _*))
          .select("researcherId", "componentId")
          .collect()
          .map(r => r.getString(0) -> r.getString(1))
          .toMap

    val (pathOf, bfsStats) = shortestPaths(
      spark,
      index,
      edgesIdx,
      pairs.map(p => (p._2, idOf(p._3), idOf(p._4))),
      cfg.pathMaxDepth,
      windowLabel,
      cp
    )
    pathsFrom(
      spark,
      pairs,
      pathOf,
      bfsStats,
      compOf,
      largest,
      largestMembers,
      diameterSeeds,
      idOf,
      index,
      edges,
      edgesIdx,
      window,
      windowLabel,
      cfg,
      ctx,
      cp
    )
  }

  /** Shortest paths for the given (pairIndex, source index, target index) queries with one batched
    * multi-source BFS (depth cap `maxDepth`). Returns the researcher IDs in path order for every
    * pair reached within the cap. Bounded: at most |pairs| * (maxDepth + 1) rows are collected.
    */
  def shortestPaths(
      spark: SparkSession,
      index: DataFrame,
      edgesIdx: DataFrame,
      pairs: Seq[(Int, Long, Long)],
      maxDepth: Int,
      windowLabel: String,
      cp: Algorithms.Checkpoint
  ): (Map[Int, Seq[String]], IterStats) = {
    import spark.implicits._
    val sourceIds = pairs.map(_._2).distinct
    val (visited, bfsStats) = Algorithms.multiSourceBfs(
      edgesIdx,
      sourceIds.toDF("source"),
      maxDepth,
      windowLabel,
      "bfs-path-evidence",
      cp
    )
    val targets = pairs.toDF("pairIndex", "source", "target")
    val found = targets
      .join(
        visited.select(col("source"), col("node").as("target"), col("dist")),
        Seq("source", "target")
      )
      .select(col("pairIndex"), col("source"), col("target").as("node"), col("dist").as("pos"))
    var chain: DataFrame = found
    var cur: DataFrame = found
    var steps = 0
    while (steps < maxDepth && !cur.isEmpty) {
      steps += 1
      cur = cp(
        cur
          .join(visited.select("source", "node", "parent"), Seq("source", "node"))
          .where(col("parent").isNotNull)
          .select(
            col("pairIndex"),
            col("source"),
            col("parent").as("node"),
            (col("pos") - 1).as("pos")
          )
      )
      chain = chain.unionByName(cur)
    }
    // Bounded: at most |pairs| * (pathMaxDepth + 1) rows.
    val chainRows = chain
      .select("pairIndex", "pos", "node")
      .collect()
      .map(r => (r.getInt(0), r.getInt(1), r.getLong(2)))
    val nodeIdx = chainRows.map(_._3).distinct.toSeq
    val ridOf: Map[Long, String] =
      if (nodeIdx.isEmpty) Map.empty
      else
        index
          .where(col("id").isin(nodeIdx: _*))
          .select("id", "researcherId")
          .collect()
          .map(r => r.getLong(0) -> r.getString(1))
          .toMap
    val pathOf: Map[Int, Seq[String]] =
      chainRows.groupBy(_._1).map { case (k, rows) =>
        k -> rows.sortBy(_._2).map(r => ridOf(r._3)).toSeq
      }
    (pathOf, bfsStats)
  }

  private def pathsFrom(
      spark: SparkSession,
      pairs: Seq[(String, Int, String, String)],
      pathOf: Map[Int, Seq[String]],
      bfsStats: IterStats,
      compOf: Map[String, String],
      largest: Option[(String, Long)],
      largestMembers: DataFrame,
      diameterSeeds: Seq[String],
      idOf: Map[String, Long],
      index: DataFrame,
      edges: Dataset[CoauthorEdgeRow],
      edgesIdx: DataFrame,
      window: YearWindow,
      windowLabel: String,
      cfg: AlgorithmSettings,
      ctx: ProvCtx,
      cp: Algorithms.Checkpoint
  ): PathsOutcome = {
    import spark.implicits._
    // Hop facts from the CoauthorEdge of each pair in this window (bounded join).
    val hopKeys = pathOf.values
      .flatMap(p => p.zip(p.drop(1)))
      .map { case (a, b) =>
        if (CodePointOrder.lessThan(a, b)) (a, b) else (b, a)
      }
      .toSeq
      .distinct
    val edgeOf: Map[(String, String), CoauthorEdgeRow] =
      if (hopKeys.isEmpty) Map.empty
      else
        edges
          .join(hopKeys.toDF("left", "right"), Seq("left", "right"))
          .select(edges.columns.toIndexedSeq.map(col): _*)
          .as[CoauthorEdgeRow]
          .collect()
          .map(e => (e.left, e.right) -> e)
          .toMap

    val pathRows = pairs.flatMap { case (kind, i, a, b) =>
      pathOf.get(i).map { p =>
        val hops = p.zip(p.drop(1)).zipWithIndex.map { case ((x, y), h) =>
          val key = if (CodePointOrder.lessThan(x, y)) (x, y) else (y, x)
          val e = edgeOf(key)
          PathHop(h + 1, x, y, e.edgeId, e.weight, e.firstYear, e.lastYear, e.supportingWorkIds)
        }
        PathEvidenceRow(
          kind,
          i,
          a,
          b,
          hops.size,
          EvidenceItemRow.of(EvidenceType.GraphPath, p, window, None, None, ctx.row(JobPaths, p)),
          hops
        )
      }
    }
    val queries = pairs.map { case (kind, i, a, b) =>
      val p = pathOf.get(i)
      PathQueryResult(kind, i, a, b, compOf.get(a) == compOf.get(b), p.nonEmpty, p.map(_.size - 1))
    }

    // Effective diameter: one batched BFS from the sampled sources, depth cap diameterMaxDepth.
    val (dVisited, dStats) = Algorithms.multiSourceBfs(
      edgesIdx,
      diameterSeeds.map(idOf).toDF("source"),
      cfg.diameterMaxDepth,
      windowLabel,
      "bfs-effective-diameter",
      cp
    )
    val dist = dVisited
      .where(col("dist") > 0)
      .groupBy("dist")
      .agg(count(lit(1)).as("n"))
      .orderBy("dist")
      .collect()
      .map(r => (r.getInt(0), r.getLong(1)))
      .toSeq
    val reached = dist.map(_._2).sum
    val compSize = largest.map(_._2).getOrElse(0L)
    val possible = diameterSeeds.size.toLong * math.max(0L, compSize - 1)
    val p90 =
      if (reached == 0) None
      else {
        val cum = dist.scanLeft((0, 0L)) { case ((_, acc), (d, n)) => (d, acc + n) }.drop(1)
        cum.find { case (_, acc) => acc.toDouble >= 0.9 * reached.toDouble }.map(_._1)
      }
    val mean =
      if (reached == 0) None
      else Some(dist.map { case (d, n) => d.toDouble * n }.sum / reached.toDouble)
    PathsOutcome(
      pathRows,
      queries,
      DiameterEstimate(
        diameterSeeds.size,
        cfg.diameterMaxDepth,
        compSize,
        reached,
        possible - reached,
        dist,
        p90,
        mean
      ),
      largest.map(_._1),
      compSize,
      Seq(bfsStats, dStats)
    )
  }
}
