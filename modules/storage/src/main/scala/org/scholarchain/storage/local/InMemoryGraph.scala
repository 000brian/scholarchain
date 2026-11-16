package org.scholarchain.storage.local

import java.io.{BufferedReader, InputStreamReader}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.zip.GZIPInputStream

import scala.collection.immutable.ListMap
import scala.collection.mutable

import org.scholarchain.core.GraphEdgeLabel
import org.scholarchain.storage.neptune.{CypherTemplates, NeptuneModel, OpenCypherCsv}
import org.scholarchain.storage.ports._

/** Keep only the CSV rows whose `column` (a property column, matched by name without its `:Type`)
  * holds one of `allowed`. A file without that column is an error.
  */
final case class CsvRowFilter(column: String, allowed: Set[String])

/** Rows read from one CSV file and rows kept after the filter. */
final case class CsvLoadStats(read: Long, kept: Long) {
  def +(o: CsvLoadStats): CsvLoadStats = CsvLoadStats(read + o.read, kept + o.kept)
}
object CsvLoadStats { val zero: CsvLoadStats = CsvLoadStats(0L, 0L) }

/** An in-memory property graph with the read-only query semantics of the Neptune templates, the
  * bounded MERGE upsert, and a CSV reader for the openCypher load files (so tests and the local
  * runtime can load what the Neptune builder wrote and query it).
  *
  * Queries are validated with the same [[GraphReadSettings]] as `NeptuneGraphRead` and return the
  * same columns. `PathBetween` is a breadth-first search, level by level up to `maxDepth`, that
  * stops at the first level reaching the target or when `settings.timeoutMillis` has elapsed; its
  * `complete`, `depthSearched` and `truncated` (more than one shortest path) fields mean what they
  * mean on Neptune.
  */
final class InMemoryGraph(
    val rowCap: Int,
    val settings: GraphReadSettings,
    clock: () => Long = InMemoryGraph.monotonicMillis
) extends GraphReadPort
    with GraphUpsertPort {
  final case class Node(id: String, label: String, props: Map[String, ujson.Value])
  final case class Edge(
      id: String,
      tpe: String,
      from: String,
      to: String,
      props: Map[String, ujson.Value]
  )

  private val nodes = mutable.HashMap.empty[String, Node]
  private val edges = mutable.HashMap.empty[String, Edge]
  private val adjacency = mutable.HashMap.empty[String, mutable.ArrayBuffer[Edge]]
  private val interned = mutable.HashMap.empty[String, String]

  def nodeCount: Int = nodes.size
  def edgeCount: Int = edges.size
  def node(id: String): Option[Node] = nodes.get(id)
  def edge(id: String): Option[Edge] = edges.get(id)

  /** Low-cardinality string values (provenance, window labels, venue keys) share one instance. */
  private def intern(s: String): String = interned.getOrElseUpdate(s, s)

  /** One shared JSON value per distinct low-cardinality string (millions of edges share them). */
  private val internedValues = mutable.HashMap.empty[String, ujson.Str]
  private def internedStr(s: String): ujson.Str =
    internedValues.getOrElseUpdate(s, ujson.Str(intern(s)))

  def addNode(id: String, label: String, props: Map[String, ujson.Value]): Unit = synchronized {
    val merged = nodes.get(id).map(_.props).getOrElse(Map.empty) ++ props
    nodes.update(id, Node(id, intern(label), merged))
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
      val e = Edge(id, intern(tpe), from, to, props)
      edges.get(id) match {
        case None =>
          adjacency.getOrElseUpdate(from, mutable.ArrayBuffer.empty) += e
          if (to != from) adjacency.getOrElseUpdate(to, mutable.ArrayBuffer.empty) += e
        case Some(old) =>
          List(old.from, old.to).distinct.foreach(n =>
            adjacency.get(n).foreach(b => b.update(b.indexWhere(_.id == id), e))
          )
      }
      edges.update(id, e)
    }

  private val InternedColumns =
    Set("runId", "datasetId", "snapshotId", "policyVersion", "windowLabel", "venueKey")

  /** Loads one openCypher CSV file (plain or `.gz`) written by the Neptune builder, optionally
    * keeping only the rows that pass `filter`. Read-only toward the file.
    */
  def loadCsv(file: Path, filter: Option[CsvRowFilter] = None): CsvLoadStats = {
    val raw = Files.newInputStream(file)
    val in = if (file.toString.endsWith(".gz")) new GZIPInputStream(raw, 1 << 16) else raw
    val r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 16)
    try {
      val header = OpenCypherCsv.parseLine(r.readLine())
      val isEdge = header.contains(":START_ID")
      val props = header.zipWithIndex.filterNot(_._1.startsWith(":")).map { case (h, i) =>
        val Array(name, tpe) = h.split(":", 2)
        (name, tpe, i)
      }
      def col(n: String) = header.indexOf(n)
      val filterIdx = filter.map { f =>
        props
          .collectFirst { case (name, _, i) if name == f.column => i }
          .getOrElse(throw new IllegalArgumentException(s"$file has no column '${f.column}'"))
      }
      val (idC, labelC, typeC, startC, endC) =
        (col(":ID"), col(":LABEL"), col(":TYPE"), col(":START_ID"), col(":END_ID"))
      var read = 0L
      var kept = 0L
      var line = r.readLine()
      while (line != null) {
        val f = OpenCypherCsv.parseLine(line)
        read += 1
        val keep = filterIdx.forall(i => filter.get.allowed.contains(f(i)))
        if (keep) {
          val p = props.collect {
            case (name, tpe, i) if f(i).nonEmpty =>
              name -> (tpe match {
                case "Int" | "Long"             => ujson.Num(f(i).toLong.toDouble)
                case "Double"                   => ujson.Num(f(i).toDouble)
                case "Bool"                     => ujson.Bool(f(i).toBoolean)
                case _ if InternedColumns(name) => internedStr(f(i))
                case _                          => ujson.Str(f(i))
              })
          }.toMap
          if (isEdge) addEdge(f(idC), f(typeC), f(startC), f(endC), p)
          else addNode(f(idC), f(labelC), p)
          kept += 1
        }
        line = r.readLine()
      }
      CsvLoadStats(read, kept)
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

  private val CoauthoredWith = NeptuneModel.edgeType(GraphEdgeLabel.CoauthoredWith)
  private val Authored = NeptuneModel.edgeType(GraphEdgeLabel.Authored)

  private def coauthorEdges(id: String, window: String): Iterator[Edge] =
    adjacency
      .get(id)
      .iterator
      .flatMap(_.iterator)
      .filter(e =>
        e.tpe == CoauthoredWith && e.props.get("windowLabel").contains(ujson.Str(window))
      )

  private def other(e: Edge, id: String) = if (e.from == id) e.to else e.from

  def run(query: GraphQuery): Either[String, GraphResult] = synchronized {
    GraphQuery.validate(query, settings).map {
      case q @ GraphQuery.ResearcherById(r) =>
        val rows = nodes.get(r.value).filter(_.label == "Researcher").toList.map { n =>
          GraphRow(
            ListMap("researcherId" -> ujson.Str(n.id), "properties" -> ujson.Obj.from(n.props))
          )
        }
        GraphResult.bounded(q.templateId, rows, q.limit)
      case q @ GraphQuery.CoauthorNeighbors(r, w, l) =>
        val rows = coauthorEdges(r.value, w).toList
          .map(e => (e, other(e, r.value)))
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
        GraphResult.bounded(q.templateId, rows, l)
      case q @ GraphQuery.CoauthorEdgeBetween(a, b, w) =>
        val rows =
          coauthorEdges(a.value, w).filter(e => other(e, a.value) == b.value).toList.map { e =>
            GraphRow(ListMap("edgeId" -> ujson.Str(e.id), "properties" -> ujson.Obj.from(e.props)))
          }
        GraphResult.bounded(q.templateId, rows, q.limit)
      case q: GraphQuery.PathBetween         => shortestPath(q)
      case q @ GraphQuery.WorkAuthors(wk, l) =>
        val rows = adjacency
          .get(wk.value)
          .iterator
          .flatMap(_.iterator)
          .filter(e => e.tpe == Authored && e.to == wk.value)
          .map(_.from)
          .toList
          .distinct
          .sorted
          .map(r =>
            GraphRow(
              ListMap(
                "researcherId" -> ujson.Str(r),
                "displayName" -> nodes(r).props.getOrElse("displayName", ujson.Null)
              )
            )
          )
        GraphResult.bounded(q.templateId, rows, l)
      case q @ GraphQuery.WorkById(wk) =>
        val rows = nodes.get(wk.value).filter(_.label == "Work").toList.map { n =>
          def p(k: String) = n.props.getOrElse(k, ujson.Null)
          GraphRow(
            ListMap(
              "workId" -> ujson.Str(n.id),
              "displayName" -> p("displayName"),
              "year" -> p("year"),
              "venueKey" -> p("venueKey"),
              "dblpKey" -> p("dblpKey"),
              "teamSize" -> p("teamSize")
            )
          )
        }
        GraphResult.bounded(q.templateId, rows, q.limit)
    }
  }

  /** Level-by-level BFS from `from`. The target's shortest-path count is tracked (capped at 2) so
    * `truncated` reports more than one shortest path, as Neptune's `LIMIT 2` does.
    */
  private def shortestPath(q: GraphQuery.PathBetween): GraphResult = {
    val start = clock()
    val (a, b, w, d) = (q.from.value, q.to.value, q.windowLabel, q.maxDepth)
    val prev = mutable.HashMap(a -> (null: Edge))
    val level = mutable.HashMap(a -> 0)
    val paths = mutable.HashMap(a -> 1)
    var frontier = Vector(a)
    var depth = 0
    var timedOut = false
    while (frontier.nonEmpty && depth < d && !prev.contains(b) && !timedOut) {
      val next = Vector.newBuilder[String]
      val it = frontier.sorted.iterator
      while (it.hasNext && !timedOut) {
        if (clock() - start >= settings.timeoutMillis) timedOut = true
        else {
          val u = it.next()
          coauthorEdges(u, w).toList.sortBy(e => other(e, u)).foreach { e =>
            val v = other(e, u)
            level.get(v) match {
              case None =>
                prev.update(v, e); level.update(v, depth + 1); paths.update(v, paths(u))
                next += v
              case Some(lv) if lv == depth + 1 =>
                paths.update(v, math.min(2, paths(v) + paths(u)))
              case _ => ()
            }
          }
        }
      }
      if (!timedOut) { depth += 1; frontier = next.result() }
    }
    if (timedOut)
      GraphResult(q.templateId, Nil, truncated = false, complete = false, Some(depth))
    else if (!prev.contains(b))
      GraphResult(q.templateId, Nil, truncated = false, complete = true, Some(d))
    else {
      val ids = mutable.ListBuffer(b)
      val evs = mutable.ListBuffer.empty[String]
      var cur = b
      while (cur != a) {
        val e = prev(cur)
        evs.prepend(e.props.get("evidenceKey").map(_.str).getOrElse(e.id))
        cur = other(e, cur)
        ids.prepend(cur)
      }
      val row = GraphRow(
        ListMap(
          "researcherIds" -> ujson.Arr.from(ids.map(ujson.Str(_))),
          "evidenceKeys" -> ujson.Arr.from(evs.map(ujson.Str(_))),
          "hops" -> ujson.Num(evs.size.toDouble)
        )
      )
      GraphResult(q.templateId, List(row), paths(b) > 1, complete = true, Some(evs.size))
    }
  }
}

object InMemoryGraph {
  val monotonicMillis: () => Long = () => System.nanoTime() / 1000000L
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
