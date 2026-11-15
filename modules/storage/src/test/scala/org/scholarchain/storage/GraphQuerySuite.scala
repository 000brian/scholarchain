package org.scholarchain.storage

import scala.collection.immutable.ListMap

import org.scholarchain.core.{ResearcherId, WorkId}
import org.scholarchain.storage.local.InMemoryGraph
import org.scholarchain.storage.neptune.CypherTemplates
import org.scholarchain.storage.ports.{GraphQuery, NodeUpsert}

class GraphQuerySuite extends munit.FunSuite {
  import TestFixtures._

  test("compiled queries carry identifiers only as parameters") {
    val queries = List(
      GraphQuery.ResearcherById(rA),
      GraphQuery.CoauthorNeighbors(rA, Full, 25),
      GraphQuery.CoauthorEdgeBetween(rA, rB, Full),
      GraphQuery.PathBetween(rA, rD, Full, 6),
      GraphQuery.WorkAuthors(w1, 10)
    )
    queries.foreach { q =>
      val c = CypherTemplates.compile(q).toOption.get
      assert(
        !c.cypher.contains(rA.value) && !c.cypher.contains(w1.value) && !c.cypher.contains(Full),
        c.cypher
      )
      assert(c.parameters.value.nonEmpty)
      assert(c.cypher.contains("LIMIT"), c.cypher)
    }
  }

  test("depth, limit and identifier bounds are enforced before any query text is chosen") {
    assert(CypherTemplates.compile(GraphQuery.PathBetween(rA, rD, Full, 7)).isLeft)
    assert(CypherTemplates.compile(GraphQuery.PathBetween(rA, rD, Full, 0)).isLeft)
    assert(CypherTemplates.compile(GraphQuery.PathBetween(rA, rA, Full, 3)).isLeft)
    assert(
      CypherTemplates
        .compile(GraphQuery.CoauthorNeighbors(rA, Full, GraphQueryBounds.MaxResultLimit + 1))
        .isLeft
    )
    assert(CypherTemplates.compile(GraphQuery.CoauthorNeighbors(rA, Full, 0)).isLeft)
    assert(
      CypherTemplates
        .compile(GraphQuery.CoauthorNeighbors(ResearcherId("x' OR 1=1"), Full, 5))
        .isLeft
    )
    assert(
      CypherTemplates
        .compile(GraphQuery.CoauthorNeighbors(rA, "w}) MATCH (n) RETURN n //", 5))
        .isLeft
    )
    assert(CypherTemplates.compile(GraphQuery.WorkAuthors(WorkId("nope"), 5)).isLeft)
    assertEquals(CypherTemplates.PathBetween.keySet, (1 to 6).toSet)
  }

  test("no template writes or removes data") {
    val all = List(
      CypherTemplates.ResearcherById,
      CypherTemplates.CoauthorNeighbors,
      CypherTemplates.CoauthorEdgeBetween,
      CypherTemplates.WorkAuthors
    ) ++
      CypherTemplates.PathBetween.values
    val forbidden = List("CREATE", "MERGE", "SET ", "DELETE", "REMOVE", "DROP", "CALL")
    all.foreach(t => forbidden.foreach(f => assert(!t.toUpperCase.contains(f), s"$f in $t")))
  }

  test("MERGE upserts are capped at the configured row cap and use allowlisted labels") {
    val g = new InMemoryGraph(rowCap = 2)
    val rows =
      List("a", "b", "c").map(i => NodeUpsert(s"dblp:$i", ListMap("displayName" -> ujson.Str(i))))
    assert(g.upsertNodes("DatasetSnapshot", rows).isLeft)
    assertEquals(g.upsertNodes("DatasetSnapshot", rows.take(2)), Right(2))
    assert(g.upsertNodes("Anything) DETACH", rows.take(1)).isLeft)
    assert(CypherTemplates.mergeNodes("Researcher").toOption.get.contains("$rows"))
  }

  test("neighbour results are truncated at the limit and say so") {
    val g = new InMemoryGraph(10)
    researchers.foreach(r =>
      g.addNode(r.value, "Researcher", Map("displayName" -> ujson.Str(names(r))))
    )
    graphCoauthorEdges.foreach(e =>
      g.addEdge(
        e.edgeId.value,
        "COAUTHORED_WITH",
        e.from,
        e.to,
        Map("windowLabel" -> ujson.Str(Full), "weight" -> ujson.Num(1))
      )
    )
    val r = g.run(GraphQuery.CoauthorNeighbors(rB, Full, 1)).toOption.get
    assertEquals(r.rows.size, 1)
    assert(r.truncated)
    assertEquals(g.run(GraphQuery.CoauthorNeighbors(rB, "w2015-2017", 5)).toOption.get.rows, Nil)
  }
}
