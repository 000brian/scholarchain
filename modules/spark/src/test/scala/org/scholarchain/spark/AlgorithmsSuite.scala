package org.scholarchain.spark

import java.io.File

import scala.concurrent.duration._

import org.apache.spark.sql.{DataFrame, SparkSession}

/** Algorithm-level known-graph cases that the pipeline fixture does not isolate: a long chain for
  * large-star/small-star, an empty graph, label-propagation oscillation without the self vote, a
  * star for k-core, and batched BFS distances.
  */
class AlgorithmsSuite extends munit.FunSuite {

  override val munitTimeout: Duration = 10.minutes

  private var tmp: File = _
  private var spark: SparkSession = _
  private val cp: Algorithms.Checkpoint = df => df.localCheckpoint()

  override def beforeAll(): Unit = {
    tmp = SparkTestSupport.tempDir("algorithms")
    spark = SparkTestSupport.session(tmp)
  }

  override def afterAll(): Unit = if (spark != null) spark.stop()

  private def vertices(ids: Long*): DataFrame = {
    val s = spark
    import s.implicits._
    ids.toDF("id")
  }

  private def edges(es: (Long, Long, Double)*): DataFrame = {
    val s = spark
    import s.implicits._
    es.map { case (a, b, w) => (math.min(a, b), math.max(a, b), w) }.toDF("u", "v", "w")
  }

  private def asMap(df: DataFrame, value: String): Map[Long, Long] =
    df.select("id", value).collect().map(r => r.getLong(0) -> r.getLong(1)).toMap

  test("components: a long chain, a pair and an isolated vertex; IDs are the smallest member") {
    val chain = (0L until 12L).map(i => (i, i + 1, 1.0))
    // Shuffle the chain's vertex order relative to its index order to exercise both star steps.
    val relabel = Map(
      0L -> 5L,
      1L -> 11L,
      2L -> 0L,
      3L -> 7L,
      4L -> 2L,
      5L -> 12L,
      6L -> 9L,
      7L -> 1L,
      8L -> 4L,
      9L -> 10L,
      10L -> 3L,
      11L -> 6L,
      12L -> 8L
    )
    val es = chain.map { case (a, b, w) => (relabel(a), relabel(b), w) } :+ ((20L, 21L, 1.0))
    val (labels, stats) = Algorithms.connectedComponents(
      vertices((0L to 12L) ++ Seq(20L, 21L, 30L): _*),
      edges(es: _*),
      30,
      "t",
      cp
    )
    val m = asMap(labels, "component")
    (0L to 12L).foreach(i => assertEquals(m(i), 0L, s"vertex $i"))
    assertEquals((m(20L), m(21L), m(30L)), (20L, 20L, 30L))
    assert(stats.converged, stats.toString)
    assert(stats.iterations < 30, stats.toString)
    assertEquals(stats.changes.last, 0L)
  }

  test("empty graph: every vertex is its own component and community, coreness 0") {
    val v = vertices(3L, 1L, 2L)
    val e = edges()
    val (comp, cs) = Algorithms.connectedComponents(v, e, 30, "t", cp)
    val (comm, ls) = Algorithms.labelPropagation(v, e, 10, 1.0, "t", cp)
    val (core, ks) = Algorithms.coreness(v, e, 30, "t", cp)
    assertEquals(asMap(comp, "component"), Map(1L -> 1L, 2L -> 2L, 3L -> 3L))
    assertEquals(asMap(comm, "label"), Map(1L -> 1L, 2L -> 2L, 3L -> 3L))
    assertEquals(asMap(core, "core"), Map(1L -> 0L, 2L -> 0L, 3L -> 0L))
    assert(cs.converged && ls.converged && ks.converged)
  }

  test(
    "label propagation: plain synchronous voting oscillates on a pair; the self vote converges"
  ) {
    // Two coauthors with three joint works: the self vote must scale with the edge weight.
    val v = vertices(0L, 1L)
    val e = edges((0L, 1L, 3.0))
    val (plain, ps) = Algorithms.labelPropagation(v, e, 4, 0.0, "t", cp)
    assert(!ps.converged, ps.toString) // labels swap every iteration (documented limit)
    assertEquals(ps.changes, Seq(2L, 2L, 2L, 2L))
    assertEquals(asMap(plain, "label"), Map(0L -> 0L, 1L -> 1L))
    val (self, ss) = Algorithms.labelPropagation(v, e, 4, 1.0, "t", cp)
    assert(ss.converged, ss.toString)
    assertEquals(asMap(self, "label"), Map(0L -> 0L, 1L -> 0L)) // tie: smallest label
  }

  test("label propagation follows edge weights over the smallest label") {
    // 1 is pulled to 3 by a heavy edge although label 0 is smaller.
    val v = vertices(0L, 1L, 3L)
    val e = edges((0L, 1L, 1.0), (1L, 3L, 5.0))
    val (labels, _) = Algorithms.labelPropagation(v, e, 10, 1.0, "t", cp)
    val m = asMap(labels, "label")
    assertEquals(m(1L), m(3L))
  }

  test("k-core: star, triangle with pendant") {
    val star = edges((0L, 1L, 1.0), (0L, 2L, 1.0), (0L, 3L, 1.0), (0L, 4L, 1.0))
    val (c1, s1) = Algorithms.coreness(vertices(0L, 1L, 2L, 3L, 4L), star, 30, "t", cp)
    assertEquals(asMap(c1, "core"), (0L to 4L).map(_ -> 1L).toMap)
    assert(s1.converged)
    val tri = edges((0L, 1L, 1.0), (1L, 2L, 1.0), (0L, 2L, 1.0), (2L, 3L, 9.0))
    val (c2, _) = Algorithms.coreness(vertices(0L, 1L, 2L, 3L), tri, 30, "t", cp)
    assertEquals(asMap(c2, "core"), Map(0L -> 2L, 1L -> 2L, 2L -> 2L, 3L -> 1L))
  }

  test("batched BFS: all sources advance together; distances and smallest-parent ties") {
    val s = spark
    import s.implicits._
    // Square 0-1-3, 0-2-3: two shortest paths 0->3; the parent of 3 is the smaller of 1 and 2.
    val e = edges((0L, 1L, 1.0), (0L, 2L, 1.0), (1L, 3L, 1.0), (2L, 3L, 1.0), (3L, 4L, 1.0))
    val (visited, stats) =
      Algorithms.multiSourceBfs(e, Seq(0L, 4L).toDF("source"), 6, "t", "bfs", cp)
    val rows = visited
      .collect()
      .map(r =>
        (r.getLong(0), r.getLong(1)) -> (r.getInt(2), Option(r.get(3)).map(_.asInstanceOf[Long]))
      )
      .toMap
    assertEquals(rows((0L, 3L)), (2, Some(1L)))
    assertEquals(rows((0L, 4L)), (3, Some(3L)))
    assertEquals(rows((4L, 0L)), (3, Some(1L)))
    assertEquals(rows((0L, 0L)), (0, None))
    assertEquals(rows.size, 10)
    assert(stats.converged)
    val (_, capped) = Algorithms.multiSourceBfs(e, Seq(0L).toDF("source"), 1, "t", "bfs", cp)
    assert(!capped.converged, "depth cap reached before the frontier emptied")
  }
}
