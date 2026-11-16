package org.scholarchain.storage

import scala.collection.immutable.ListMap

import org.scholarchain.core.{MentionId, ResearcherId, WorkId}
import org.scholarchain.storage.local.InMemoryGraph
import org.scholarchain.storage.neptune.CypherTemplates
import org.scholarchain.storage.ports.{GraphQuery, GraphReadSettings, NodeUpsert}

class GraphQuerySuite extends munit.FunSuite {
  import TestFixtures._

  private val W2 = "w2015-2017"

  private def sampleQueries(window: String, limit: Int): List[GraphQuery] = List(
    GraphQuery.ResearcherById(rA),
    GraphQuery.CoauthorNeighbors(rA, window, limit),
    GraphQuery.CoauthorEdgeBetween(rA, rB, window),
    GraphQuery.PathBetween(rA, rD, window, 4),
    GraphQuery.WorkAuthors(w1, limit),
    GraphQuery.WorkById(w1)
  )

  private def compile(q: GraphQuery) = CypherTemplates.compile(q, graphSettings)

  test("A2: identifiers never appear in query text; they travel only as parameters") {
    sampleQueries(Full, 25).foreach { q =>
      compile(q).toOption.get.foreach { c =>
        List(rA, rB, rD).map(_.value).foreach(id => assert(!c.cypher.contains(id), c.cypher))
        assert(!c.cypher.contains(w1.value), c.cypher)
        assert(c.parameters.value.nonEmpty, c.templateId)
        assert(c.cypher.contains("LIMIT"), c.cypher)
      }
    }
  }

  test(
    "A2: the only caller-derived text is a validated window label (paths) or an integer"
  ) {
    // other identifiers: identical text
    val alt = List(
      GraphQuery.ResearcherById(rB),
      GraphQuery.CoauthorNeighbors(rC, Full, 25),
      GraphQuery.CoauthorEdgeBetween(rC, rD, Full),
      GraphQuery.PathBetween(rB, rC, Full, 4),
      GraphQuery.WorkAuthors(w2, 25),
      GraphQuery.WorkById(w3)
    )
    sampleQueries(Full, 25).zip(alt).foreach { case (a, b) =>
      assertEquals(compile(a).toOption.get.map(_.cypher), compile(b).toOption.get.map(_.cypher))
    }
    // another window: single-hop templates keep $windowLabel (identical text); path templates
    // differ only by the quoted window literal
    sampleQueries(Full, 25).zip(sampleQueries(W2, 25)).foreach { case (a, b) =>
      compile(a).toOption.get.zip(compile(b).toOption.get).foreach { case (x, y) =>
        if (a.isInstanceOf[GraphQuery.PathBetween]) {
          assert(x.cypher.contains(s"{windowLabel: '$Full'}"), x.cypher)
          assertEquals(x.cypher.replace(s"'$Full'", s"'$W2'"), y.cypher)
        } else assertEquals(x.cypher, y.cypher)
      }
    }
    // another limit: the text differs only by the integer limit + 1
    val l25 = compile(GraphQuery.CoauthorNeighbors(rA, Full, 25)).toOption.get.head.cypher
    val l7 = compile(GraphQuery.CoauthorNeighbors(rA, Full, 7)).toOption.get.head.cypher
    assert(l25.endsWith("LIMIT 26") && l7.endsWith("LIMIT 8"), s"$l25 | $l7")
    assertEquals(l25.replace("LIMIT 26", "LIMIT 8"), l7)
    assert(!l25.contains("$limit"), "no LIMIT parameter (A2)")
  }

  test("A2: IDs with quotes, braces or Cypher keywords, and unconfigured windows, are rejected") {
    val bad = List(
      "x' OR 1=1",
      "researcher:v1:' }) MATCH (n) DETACH DELETE n //",
      "researcher:v1:{a}",
      "MATCH (n) RETURN n",
      "researcher:v1:" + "0" * 31 + "'"
    ).map(ResearcherId(_))
    bad.foreach { r =>
      assert(compile(GraphQuery.ResearcherById(r)).isLeft, r.value)
      assert(compile(GraphQuery.CoauthorNeighbors(r, Full, 5)).isLeft, r.value)
      assert(compile(GraphQuery.PathBetween(r, rD, Full, 2)).isLeft, r.value)
    }
    List(
      "w}) MATCH (n) RETURN n //",
      "full-2015-2025'",
      "Full-2015-2025",
      "w2015-2017 ",
      "w1999-2000" // well-formed but not a configured window
    ).foreach { w =>
      assert(compile(GraphQuery.PathBetween(rA, rD, w, 2)).isLeft, w)
      assert(compile(GraphQuery.CoauthorNeighbors(rA, w, 5)).isLeft, w)
    }
    assert(compile(GraphQuery.WorkById(WorkId("work:v1:RETURN 1"))).isLeft)
    assert(compile(GraphQuery.WorkAuthors(WorkId("nope"), 5)).isLeft)
  }

  test("depth, limit and identifier bounds come from configuration") {
    assert(compile(GraphQuery.PathBetween(rA, rD, Full, 5)).isLeft, "runtime.maxPathDepth = 4")
    assert(compile(GraphQuery.PathBetween(rA, rD, Full, 0)).isLeft)
    assert(compile(GraphQuery.PathBetween(rA, rA, Full, 3)).isLeft)
    assert(compile(GraphQuery.CoauthorNeighbors(rA, Full, 51)).isLeft, "graphResultLimit = 50")
    assert(compile(GraphQuery.CoauthorNeighbors(rA, Full, 0)).isLeft)
    assertEquals(
      compile(GraphQuery.PathBetween(rA, rD, Full, 4)).toOption.get.map(_.depth),
      List(1, 2, 3, 4).map(Some(_))
    )
    intercept[IllegalArgumentException](GraphReadSettings(Set(Full), 7, 10, 1000))
    intercept[IllegalArgumentException](GraphReadSettings(Set("bad'label"), 3, 10, 1000))
  }

  test("no template writes or removes data") {
    val forbidden = List("CREATE", "MERGE", "SET ", "DELETE", "REMOVE", "DROP", "CALL")
    sampleQueries(Full, 10).flatMap(q => compile(q).toOption.get).foreach { c =>
      forbidden.foreach(f => assert(!c.cypher.toUpperCase.contains(f), s"$f in ${c.cypher}"))
    }
  }

  test("MERGE upserts are capped at the configured row cap and use allowlisted labels") {
    val g = new InMemoryGraph(rowCap = 2, graphSettings)
    val rows =
      List("a", "b", "c").map(i => NodeUpsert(s"dblp:$i", ListMap("displayName" -> ujson.Str(i))))
    assert(g.upsertNodes("DatasetSnapshot", rows).isLeft)
    assertEquals(g.upsertNodes("DatasetSnapshot", rows.take(2)), Right(2))
    assert(g.upsertNodes("Anything) DETACH", rows.take(1)).isLeft)
    assert(CypherTemplates.mergeNodes("Researcher").toOption.get.contains("$rows"))
  }

  /** A-B-C-D chain in the full window, plus extra researchers for path-count tests. */
  private def chain(clock: () => Long = InMemoryGraph.monotonicMillis): InMemoryGraph = {
    val g = new InMemoryGraph(10, graphSettings, clock)
    researchers.foreach(r =>
      g.addNode(r.value, "Researcher", Map("displayName" -> ujson.Str(names(r))))
    )
    graphCoauthorEdges.foreach(e =>
      g.addEdge(
        e.edgeId.value,
        "COAUTHORED_WITH",
        e.from,
        e.to,
        Map(
          "windowLabel" -> ujson.Str(Full),
          "weight" -> ujson.Num(1),
          "evidenceKey" -> ujson.Str(e.edgeId.value)
        )
      )
    )
    g
  }

  test("neighbour results are truncated at the limit and say so") {
    val g = chain()
    val r = g.run(GraphQuery.CoauthorNeighbors(rB, Full, 1)).toOption.get
    assertEquals(r.rows.size, 1)
    assert(r.truncated && r.complete && r.depthSearched.isEmpty)
    val two = g.run(GraphQuery.CoauthorNeighbors(rB, Full, 2)).toOption.get
    assertEquals((two.rows.size, two.truncated), (2, false))
    assertEquals(g.run(GraphQuery.CoauthorNeighbors(rB, W2, 5)).toOption.get.rows, Nil)
  }

  test("path search: found path, no path within depth d, and the time limit are distinct") {
    val g = chain()
    val found = g.run(GraphQuery.PathBetween(rA, rD, Full, 4)).toOption.get
    assertEquals(found.rows.head.values("hops").num, 3.0)
    assertEquals(found.rows.head.values("researcherIds").arr.map(_.str).toList, pathSubjects)
    assertEquals((found.complete, found.depthSearched, found.truncated), (true, Some(3), false))

    val none = g.run(GraphQuery.PathBetween(rA, rD, Full, 2)).toOption.get
    assertEquals((none.rows, none.complete, none.depthSearched), (Nil, true, Some(2)))

    // a clock that jumps past the 1000 ms budget after the first reading
    var t = 0L
    val slow = chain(() => { val now = t; t += 600; now })
    val stopped = slow.run(GraphQuery.PathBetween(rA, rD, Full, 4)).toOption.get
    assertEquals(stopped.rows, Nil)
    assert(!stopped.complete, "stopped at the time limit is not complete")
    assert(stopped.depthSearched.exists(_ < 3), stopped.toString)
  }

  test("path search reports more than one shortest path as truncated") {
    val g = chain()
    // a second 2-hop route A-E-C beside A-B-C
    val e = ResearcherId.fromAnchorMention(MentionId.of(w3, 7))
    g.addNode(e.value, "Researcher", Map("displayName" -> ujson.Str("Eve")))
    List(rA -> e, e -> rC).zipWithIndex.foreach { case ((x, y), i) =>
      g.addEdge(
        s"extra-$i",
        "COAUTHORED_WITH",
        x.value,
        y.value,
        Map("windowLabel" -> ujson.Str(Full), "weight" -> ujson.Num(1))
      )
    }
    val r = g.run(GraphQuery.PathBetween(rA, rC, Full, 4)).toOption.get
    assertEquals((r.rows.size, r.truncated, r.depthSearched), (1, true, Some(2)))
    val single = g.run(GraphQuery.PathBetween(rA, rB, Full, 4)).toOption.get
    assertEquals((single.truncated, single.depthSearched), (false, Some(1)))
  }

  test("WorkById returns title, year, venue, dblp key and team size") {
    val g = chain()
    g.addNode(
      w1.value,
      "Work",
      Map(
        "displayName" -> ujson.Str("A title"),
        "year" -> ujson.Num(2016),
        "venueKey" -> ujson.Str("conf/x"),
        "dblpKey" -> ujson.Str("conf/x/A16"),
        "teamSize" -> ujson.Num(2)
      )
    )
    val r = g.run(GraphQuery.WorkById(w1)).toOption.get
    assertEquals(
      r.rows.head.values.keys.toList,
      List("workId", "displayName", "year", "venueKey", "dblpKey", "teamSize")
    )
    assertEquals(r.rows.head.values("displayName").str, "A title")
    assertEquals(r.rows.head.values("teamSize").num, 2.0)
    assertEquals(g.run(GraphQuery.WorkById(w2)).toOption.get.rows, Nil)
    assertEquals(g.run(GraphQuery.WorkById(WorkId(rA.value))).isLeft, true)
  }
}
