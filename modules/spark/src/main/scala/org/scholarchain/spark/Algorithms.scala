package org.scholarchain.spark

import org.apache.spark.sql.{Column, DataFrame}
import org.apache.spark.sql.expressions.Window
import org.apache.spark.sql.functions._

/** Convergence record of one iterative algorithm run in one window. `changes(i)` is the number of
  * changed edges, labels or core values after iteration i + 1 (for BFS: newly reached nodes).
  */
final case class IterStats(
    algorithm: String,
    windowLabel: String,
    maxIterations: Int,
    iterations: Int,
    converged: Boolean,
    changes: Seq[Long]
)

/** Iterative graph algorithms in plain DataFrame code (no GraphX, no GraphFrames).
  *
  * Vertices are dense `Long` indexes assigned in ResearcherId code-point order (see `VertexIndex`),
  * so "smallest index" is "smallest ResearcherId". Undirected edges arrive as `(u, v, w)` with
  * `u < v`, one row per pair. Nothing is collected to the driver except per-iteration counts. `cp`
  * checkpoints a DataFrame (reliable checkpoint in the run) to cut the lineage of every iteration.
  */
object Algorithms {

  type Checkpoint = DataFrame => DataFrame

  /** Both directions of every undirected edge: (src, dst, w). */
  def directed(edges: DataFrame): DataFrame =
    edges
      .select(col("u").as("src"), col("v").as("dst"), col("w"))
      .unionByName(edges.select(col("v").as("src"), col("u").as("dst"), col("w")))

  /** Connected components by alternating large-star and small-star (Kiveris et al., 2014).
    *
    * The edge set is kept as `(hi, lo)` with `hi > lo`. Large-star links every strictly larger
    * neighbour of a node to the node's minimum neighbourhood label; small-star links a node and its
    * smaller neighbours to their minimum. At the fixed point every component is a star centred on
    * its minimum vertex, so the component ID is the smallest member index (the smallest
    * ResearcherId). Converged means a full round left the edge set unchanged.
    *
    * @return
    *   (id, component) for every vertex, and the convergence record
    */
  def connectedComponents(
      vertices: DataFrame,
      edges: DataFrame,
      maxIterations: Int,
      windowLabel: String,
      cp: Checkpoint
  ): (DataFrame, IterStats) = {
    var e: DataFrame =
      cp(
        edges
          .select(greatest(col("u"), col("v")).as("hi"), least(col("u"), col("v")).as("lo"))
          .distinct()
      )
    var iterations = 0
    var converged = false
    val changes = scala.collection.mutable.ArrayBuffer.empty[Long]
    while (!converged && iterations < maxIterations) {
      iterations += 1
      // Large-star.
      val both = e
        .select(col("hi").as("x"), col("lo").as("y"))
        .unionByName(e.select(col("lo").as("x"), col("hi").as("y")))
      val m = both
        .groupBy("x")
        .agg(min("y").as("my"))
        .select(col("x"), least(col("x"), col("my")).as("m"))
      val ls = both
        .join(m, "x")
        .where(col("y") > col("x"))
        .select(col("y").as("hi"), col("m").as("lo"))
        .distinct()
      val lsC = cp(ls)
      // Small-star.
      val ms = lsC.groupBy("hi").agg(min("lo").as("m"))
      val joined = lsC.join(ms, "hi")
      val ss = joined
        .where(col("lo") =!= col("m"))
        .select(col("lo").as("hi"), col("m").as("lo"))
        .unionByName(joined.select(col("hi"), col("m").as("lo")))
        .distinct()
      val next = cp(ss)
      val changed = next.exceptAll(e).count() + e.exceptAll(next).count()
      changes += changed
      converged = changed == 0
      e = next
    }
    val assigned = e.select(col("hi").as("id"), col("lo").as("component"))
    val labels = vertices
      .select("id")
      .join(assigned, Seq("id"), "left")
      .select(col("id"), coalesce(col("component"), col("id")).as("component"))
    (
      labels,
      IterStats(
        "connected-components-large-small-star",
        windowLabel,
        maxIterations,
        iterations,
        converged,
        changes.toList
      )
    )
  }

  /** Weighted synchronous label propagation.
    *
    * Every vertex starts with its own index as label. In each iteration a vertex scores every label
    * by the summed weight of its edges to neighbours carrying it, plus a self vote for its own
    * current label of `selfWeight` times its maximum incident edge weight, and takes the best
    * label; ties go to the smallest label. Stops when no label changes or after `maxIterations`
    * (then `converged = false`).
    *
    * The self vote is what stops the classic two-colour oscillation of synchronous propagation:
    * with `selfWeight = 0` two coauthors swap labels forever; with `selfWeight = 1` their votes tie
    * and both take the smaller label. A pendant researcher (one coauthor) ties between its own
    * label and its neighbour's, so it joins the neighbour's community only when that label is
    * smaller.
    */
  def labelPropagation(
      vertices: DataFrame,
      edges: DataFrame,
      maxIterations: Int,
      selfWeight: Double,
      windowLabel: String,
      cp: Checkpoint
  ): (DataFrame, IterStats) = {
    val both = directed(edges)
    val maxW = cp(both.groupBy("src").agg(max("w").as("maxW")))
    var labels: DataFrame = cp(vertices.select(col("id"), col("id").as("label")))
    var iterations = 0
    var converged = false
    val changes = scala.collection.mutable.ArrayBuffer.empty[Long]
    while (!converged && iterations < maxIterations) {
      iterations += 1
      val votes = both
        .join(labels.select(col("id").as("dst"), col("label")), "dst")
        .select(col("src"), col("label"), col("w"))
      val withSelf =
        if (selfWeight > 0.0)
          votes.unionByName(
            labels
              .join(maxW.withColumnRenamed("src", "id"), "id")
              .select(col("id").as("src"), col("label"), (col("maxW") * lit(selfWeight)).as("w"))
          )
        else votes
      val best = withSelf
        .groupBy("src", "label")
        .agg(sum("w").as("score"))
        .groupBy("src")
        .agg(max(struct(col("score"), negate(col("label")).as("negLabel"))).as("b"))
        .select(col("src").as("id"), negate(col("b.negLabel")).as("newLabel"))
      val next = cp(
        labels
          .join(best, Seq("id"), "left")
          .select(col("id"), coalesce(col("newLabel"), col("label")).as("label"))
      )
      val changed = next
        .join(labels.select(col("id"), col("label").as("old")), "id")
        .where(col("label") =!= col("old"))
        .count()
      changes += changed
      converged = changed == 0
      labels = next
    }
    (
      labels,
      IterStats(
        "label-propagation-weighted-synchronous",
        windowLabel,
        maxIterations,
        iterations,
        converged,
        changes.toList
      )
    )
  }

  /** Coreness by the distributed h-index iteration (Lü et al., 2016).
    *
    * Values start at the unweighted degree; each iteration replaces a vertex's value by the h-index
    * of its neighbours' values (the largest h such that at least h neighbours have value >= h),
    * never increasing it. The fixed point is the k-core number. Isolated vertices have 0.
    */
  def coreness(
      vertices: DataFrame,
      edges: DataFrame,
      maxIterations: Int,
      windowLabel: String,
      cp: Checkpoint
  ): (DataFrame, IterStats) = {
    val both = directed(edges).select("src", "dst")
    val degree = both.groupBy("src").agg(count(lit(1)).as("deg"))
    var core: DataFrame = cp(
      vertices
        .select("id")
        .join(degree.withColumnRenamed("src", "id"), Seq("id"), "left")
        .select(col("id"), coalesce(col("deg"), lit(0L)).as("core"))
    )
    var iterations = 0
    var converged = false
    val changes = scala.collection.mutable.ArrayBuffer.empty[Long]
    val byValue = Window.partitionBy("src").orderBy(col("v").desc)
    while (!converged && iterations < maxIterations) {
      iterations += 1
      val h = both
        .join(core.select(col("id").as("dst"), col("core").as("v")), "dst")
        .withColumn("rn", row_number().over(byValue).cast("long"))
        .groupBy("src")
        .agg(max(least(col("rn"), col("v"))).as("h"))
      val next = cp(
        core
          .join(h.withColumnRenamed("src", "id"), Seq("id"), "left")
          .select(col("id"), least(col("core"), coalesce(col("h"), lit(0L))).as("core"))
      )
      val changed = next
        .join(core.select(col("id"), col("core").as("old")), "id")
        .where(col("core") =!= col("old"))
        .count()
      changes += changed
      converged = changed == 0
      core = next
    }
    (
      core,
      IterStats("kcore-hindex", windowLabel, maxIterations, iterations, converged, changes.toList)
    )
  }

  /** One batched multi-source breadth-first search: every source is tagged and all sources advance
    * together, one join per depth level, at most `maxDepth` levels.
    *
    * The parent of a newly reached node is its smallest-index neighbour on the previous level, so
    * the shortest-path tree (and every reconstructed path) is deterministic.
    *
    * @param sources
    *   DataFrame with column `source` (a bounded, small set)
    * @return
    *   visited rows (source, node, dist, parent); parent is null for the source itself
    */
  def multiSourceBfs(
      edges: DataFrame,
      sources: DataFrame,
      maxDepth: Int,
      windowLabel: String,
      algorithm: String,
      cp: Checkpoint
  ): (DataFrame, IterStats) = {
    val both = directed(edges).select("src", "dst")
    var visited: DataFrame = cp(
      sources
        .select(col("source"))
        .distinct()
        .select(
          col("source"),
          col("source").as("node"),
          lit(0).as("dist"),
          lit(null).cast("long").as("parent")
        )
    )
    var frontier: DataFrame = visited.select("source", "node")
    var depth = 0
    var exhausted = false
    val reached = scala.collection.mutable.ArrayBuffer.empty[Long]
    while (!exhausted && depth < maxDepth) {
      depth += 1
      val fresh = cp(
        frontier
          .join(both, frontier("node") === both("src"))
          .select(col("source"), col("dst").as("node"), col("node").as("parent"))
          .join(visited.select("source", "node"), Seq("source", "node"), "left_anti")
          .groupBy("source", "node")
          .agg(min("parent").as("parent"))
          .select(col("source"), col("node"), lit(depth).as("dist"), col("parent"))
      )
      val n = fresh.count()
      reached += n
      if (n == 0) exhausted = true
      else {
        visited = cp(visited.unionByName(fresh))
        frontier = fresh.select("source", "node")
      }
    }
    (visited, IterStats(algorithm, windowLabel, maxDepth, depth, exhausted, reached.toList))
  }

  private def negate(c: Column): Column = lit(0L) - c
}
