package org.scholarchain.storage.local

import java.io.{BufferedReader, InputStreamReader}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.zip.GZIPInputStream

import scala.collection.immutable.ListMap
import scala.collection.mutable

import org.scholarchain.storage.neptune.{CypherTemplates, NeptuneModel, OpenCypherCsv}
import org.scholarchain.storage.ports._

/** An in-memory property graph with the read-only query semantics of the Neptune templates, the
  * bounded MERGE upsert, and a CSV reader for the openCypher load files (so tests can load what the
  * Neptune builder wrote and query it).
  */
final class InMemoryGraph(val rowCap: Int) extends GraphReadPort with GraphUpsertPort {
  final case class Node(id: String, label: String, props: Map[String, ujson.Value])
  final case class Edge(
      id: String,
      tpe: String,
      from: String,
      to: String,
      props: Map[String, ujson.Value]
  )

  private val nodes = mutable.LinkedHashMap.empty[String, Node]
  private val edges = mutable.LinkedHashMap.empty[String, Edge]
  private val adjacency = mutable.HashMap.empty[String, mutable.ArrayBuffer[String]]

  def nodeCount: Int = nodes.size
  def edgeCount: Int = edges.size
  def node(id: String): Option[Node] = nodes.get(id)
  def edge(id: String): Option[Edge] = edges.get(id)

  def addNode(id: String, label: String, props: Map[String, ujson.Value]): Unit = synchronized {
    val merged = nodes.get(id).map(_.props).getOrElse(Map.empty) ++ props
    nodes.update(id, Node(id, label, merged))
  }

  def addEdge(
      id: String,
      tpe: String,
      from: String,
      to: String,
      props: Map[String, ujson.Value]
  ): Unit =
    synchronized {
      require(nodes.contains(from) && nodes.contains(to), s"edge $id has a missing endpoint")
      if (!edges.contains(id)) {
        adjacency.getOrElseUpdate(from, mutable.ArrayBuffer.empty) += id
        adjacency.getOrElseUpdate(to, mutable.ArrayBuffer.empty) += id
      }
      edges.update(id, Edge(id, tpe, from, to, props))
    }

  /** Loads one openCypher CSV file (plain or `.gz`) written by the Neptune builder. */
  def loadCsv(file: Path): Long = {
    val raw = Files.newInputStream(file)
    val in = if (file.toString.endsWith(".gz")) new GZIPInputStream(raw) else raw
    val r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))
    try {
      val header = OpenCypherCsv.parseLine(r.readLine())
      val isEdge = header.contains(":START_ID")
      val props = header.zipWithIndex.filterNot(_._1.startsWith(":")).map { case (h, i) =>
        val Array(name, tpe) = h.split(":", 2)
        (name, tpe, i)
      }
      def col(n: String) = header.indexOf(n)
      var n = 0L
      var line = r.readLine()
      while (line != null) {
        val f = OpenCypherCsv.parseLine(line)
        val p = props.collect {
          case (name, tpe, i) if f(i).nonEmpty =>
            name -> (tpe match {
              case "Int" | "Long" => ujson.Num(f(i).toLong.toDouble)
              case "Double"       => ujson.Num(f(i).toDouble)
              case "Bool"         => ujson.Bool(f(i).toBoolean)
              case _              => ujson.Str(f(i))
            })
        }.toMap
        if (isEdge)
          addEdge(f(col(":ID")), f(col(":TYPE")), f(col(":START_ID")), f(col(":END_ID")), p)
        else addNode(f(col(":ID")), f(col(":LABEL")), p)
        n += 1
        line = r.readLine()
      }
      n
    } finally r.close()
  }

  def upsertNodes(label: String, rows: Seq[NodeUpsert]): Either[String, Int] =
    for {
      _ <- CypherTemplates.mergeNodes(label)
      _ <- Either.cond(
        rows.size <= rowCap,
        (),
        s"${rows.size} rows exceed the MERGE row cap $rowCap"
      )
      _ <- Either.cond(rows.forall(r => r.id != null && r.id.trim.nonEmpty), (), "blank node ID")
    } yield {
      rows.foreach(r => addNode(r.id, label, r.properties))
      rows.size
    }

  private def coauthorEdges(id: String, window: String): List[Edge] =
    adjacency
      .getOrElse(id, mutable.ArrayBuffer.empty)
      .iterator
      .map(edges)
      .filter(e =>
        e.tpe == "COAUTHORED_WITH" && e.props.get("windowLabel").contains(ujson.Str(window))
      )
      .toList

  private def other(e: Edge, id: String) = if (e.from == id) e.to else e.from

  private def limited(templateId: String, rows: List[GraphRow], limit: Int) =
    GraphResult(templateId, rows.take(limit), rows.size > limit)

  def run(query: GraphQuery): Either[String, GraphResult] = synchronized {
    GraphQuery.validate(query).map {
      case q @ GraphQuery.ResearcherById(r) =>
        val rows = nodes.get(r.value).filter(_.label == "Researcher").toList.map { n =>
          GraphRow(
            ListMap("researcherId" -> ujson.Str(n.id), "properties" -> ujson.Obj.from(n.props))
          )
        }
        limited(q.templateId, rows, 1)
      case q @ GraphQuery.CoauthorNeighbors(r, w, l) =>
        val rows = coauthorEdges(r.value, w)
          .map { e =>
            val o = other(e, r.value)
            (e, o)
          }
          .sortBy { case (e, o) => (-e.props.get("weight").map(_.num).getOrElse(0.0), o) }
          .map { case (e, o) =>
            GraphRow(
              ListMap(
                "coauthorId" -> ujson.Str(o),
                "displayName" -> nodes(o).props.getOrElse("displayName", ujson.Null),
                "weight" -> e.props.getOrElse("weight", ujson.Null),
                "jointPublicationCount" -> e.props.getOrElse("jointPublicationCount", ujson.Null),
                "evidenceKey" -> e.props.getOrElse("evidenceKey", ujson.Null)
              )
            )
          }
        limited(q.templateId, rows, l)
      case q @ GraphQuery.CoauthorEdgeBetween(a, b, w) =>
        val rows = coauthorEdges(a.value, w).filter(e => other(e, a.value) == b.value).map { e =>
          GraphRow(ListMap("edgeId" -> ujson.Str(e.id), "properties" -> ujson.Obj.from(e.props)))
        }
        limited(q.templateId, rows, 1)
      case q @ GraphQuery.PathBetween(a, b, w, d) =>
        val prev = mutable.HashMap(a.value -> (null: Edge))
        var frontier = List(a.value)
        var depth = 0
        while (frontier.nonEmpty && depth < d && !prev.contains(b.value)) {
          depth += 1
          val next = mutable.ArrayBuffer.empty[String]
          frontier.sorted.foreach { u =>
            coauthorEdges(u, w).sortBy(e => other(e, u)).foreach { e =>
              val v = other(e, u)
              if (!prev.contains(v)) { prev.update(v, e); next += v }
            }
          }
          frontier = next.toList
        }
        val rows =
          if (!prev.contains(b.value)) Nil
          else {
            val ids = mutable.ListBuffer(b.value)
            val evs = mutable.ListBuffer.empty[String]
            var cur = b.value
            while (cur != a.value) {
              val e = prev(cur)
              evs.prepend(e.props.get("evidenceKey").map(_.str).getOrElse(e.id))
              cur = other(e, cur)
              ids.prepend(cur)
            }
            List(
              GraphRow(
                ListMap(
                  "researcherIds" -> ujson.Arr.from(ids.map(ujson.Str(_))),
                  "evidenceKeys" -> ujson.Arr.from(evs.map(ujson.Str(_))),
                  "hops" -> ujson.Num(evs.size.toDouble)
                )
              )
            )
          }
        limited(q.templateId, rows, 1)
      case q @ GraphQuery.WorkAuthors(wk, l) =>
        val rows = adjacency
          .getOrElse(wk.value, mutable.ArrayBuffer.empty)
          .map(edges)
          .filter(e =>
            e.tpe == NeptuneModel.edgeType(
              org.scholarchain.core.GraphEdgeLabel.Authored
            ) && e.to == wk.value
          )
          .map(_.from)
          .sorted
          .toList
          .map(r =>
            GraphRow(
              ListMap(
                "researcherId" -> ujson.Str(r),
                "displayName" -> nodes(r).props.getOrElse("displayName", ujson.Null)
              )
            )
          )
        limited(q.templateId, rows, l)
    }
  }
}

/** Records bulk-load jobs instead of calling Neptune. */
final class RecordingBulkLoader extends GraphBulkLoader {
  val jobs: mutable.ArrayBuffer[BulkLoadJob] = mutable.ArrayBuffer.empty
  def start(job: BulkLoadJob): String = synchronized {
    jobs += job
    f"local-load-${jobs.size}%04d"
  }
  def status(loadId: String): BulkLoadStatus = BulkLoadStatus(loadId, "LOAD_COMPLETED", 0L, 0L)
}
