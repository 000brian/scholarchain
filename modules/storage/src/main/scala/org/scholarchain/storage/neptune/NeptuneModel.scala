package org.scholarchain.storage.neptune

import org.scholarchain.core.{GraphEdgeLabel, GraphNodeLabel}
import org.scholarchain.storage.ports.{GraphQuery, NodeUpsert}

/** Neptune names of the core graph labels. Node and edge IDs are the core IDs verbatim. */
object NeptuneModel {

  def nodeLabel(l: GraphNodeLabel): String = l match {
    case GraphNodeLabel.Researcher      => "Researcher"
    case GraphNodeLabel.Work            => "Work"
    case GraphNodeLabel.Venue           => "Venue"
    case GraphNodeLabel.Institution     => "Institution"
    case GraphNodeLabel.Topic           => "Topic"
    case GraphNodeLabel.AuthorMention   => "AuthorMention"
    case GraphNodeLabel.DatasetSnapshot => "DatasetSnapshot"
    case GraphNodeLabel.TextChunk       => "TextChunk"
  }

  def edgeType(l: GraphEdgeLabel): String = l.wire.toUpperCase(java.util.Locale.ROOT)

  /** Labels a MERGE upsert may target. */
  val upsertLabels: Set[String] = GraphNodeLabel.values.map(nodeLabel).toSet
}

/** The allowlisted openCypher text. Every user-supplied value is a `$parameter`; the only values
  * placed in query text are allowlisted labels and the validated path depth 1..6 chosen from this
  * precomputed table.
  */
object CypherTemplates {

  val ResearcherById: String =
    "MATCH (r:Researcher) WHERE id(r) = $researcherId " +
      "RETURN id(r) AS researcherId, properties(r) AS properties LIMIT 1"

  val CoauthorNeighbors: String =
    "MATCH (r:Researcher)-[e:COAUTHORED_WITH]-(n:Researcher) " +
      "WHERE id(r) = $researcherId AND e.windowLabel = $windowLabel " +
      "RETURN id(n) AS coauthorId, n.displayName AS displayName, e.weight AS weight, " +
      "e.jointPublicationCount AS jointPublicationCount, e.evidenceKey AS evidenceKey " +
      "ORDER BY weight DESC, coauthorId ASC LIMIT $limit"

  val CoauthorEdgeBetween: String =
    "MATCH (a:Researcher)-[e:COAUTHORED_WITH]-(b:Researcher) " +
      "WHERE id(a) = $a AND id(b) = $b AND e.windowLabel = $windowLabel " +
      "RETURN id(e) AS edgeId, properties(e) AS properties LIMIT 1"

  /** Path templates for depth 1..6 (variable-length bounds cannot be parameters). */
  val PathBetween: Map[Int, String] =
    (1 to org.scholarchain.storage.GraphQueryBounds.MaxDepth).map { d =>
      d -> (s"MATCH p = (a:Researcher)-[:COAUTHORED_WITH*1..$d]-(b:Researcher) " +
        "WHERE id(a) = $from AND id(b) = $to " +
        "AND all(e IN relationships(p) WHERE e.windowLabel = $windowLabel) " +
        "RETURN [n IN nodes(p) | id(n)] AS researcherIds, " +
        "[e IN relationships(p) | e.evidenceKey] AS evidenceKeys, length(p) AS hops " +
        "ORDER BY hops ASC LIMIT 1")
    }.toMap

  val WorkAuthors: String =
    "MATCH (r:Researcher)-[:AUTHORED]->(w:Work) WHERE id(w) = $workId " +
      "RETURN id(r) AS researcherId, r.displayName AS displayName " +
      "ORDER BY researcherId ASC LIMIT $limit"

  /** MERGE upsert per allowlisted label: `$rows` is a list of `{id, props}` maps. */
  def mergeNodes(label: String): Either[String, String] =
    if (!NeptuneModel.upsertLabels.contains(label)) Left(s"label '$label' is not allowlisted")
    else
      Right(
        s"UNWIND $$rows AS row MERGE (n:$label {`~id`: row.id}) SET n += row.props " +
          "RETURN count(n) AS upserted"
      )

  /** A compiled query: template text plus its parameters as a JSON object. */
  final case class Compiled(templateId: String, cypher: String, parameters: ujson.Obj, limit: Int)

  def compile(query: GraphQuery): Either[String, Compiled] =
    GraphQuery.validate(query).map {
      case q @ GraphQuery.ResearcherById(r) =>
        Compiled(q.templateId, ResearcherById, ujson.Obj("researcherId" -> r.value), q.limit)
      case q @ GraphQuery.CoauthorNeighbors(r, w, l) =>
        Compiled(
          q.templateId,
          CoauthorNeighbors,
          ujson.Obj("researcherId" -> r.value, "windowLabel" -> w, "limit" -> l),
          l
        )
      case q @ GraphQuery.CoauthorEdgeBetween(a, b, w) =>
        Compiled(
          q.templateId,
          CoauthorEdgeBetween,
          ujson.Obj("a" -> a.value, "b" -> b.value, "windowLabel" -> w),
          q.limit
        )
      case q @ GraphQuery.PathBetween(a, b, w, d) =>
        Compiled(
          q.templateId,
          PathBetween(d),
          ujson.Obj("from" -> a.value, "to" -> b.value, "windowLabel" -> w),
          q.limit
        )
      case q @ GraphQuery.WorkAuthors(wk, l) =>
        Compiled(q.templateId, WorkAuthors, ujson.Obj("workId" -> wk.value, "limit" -> l), l)
    }

  def mergeParameters(rows: Seq[NodeUpsert]): ujson.Obj =
    ujson.Obj("rows" -> ujson.Arr.from(rows.map { r =>
      ujson.Obj("id" -> r.id, "props" -> ujson.Obj.from(r.properties))
    }))
}
