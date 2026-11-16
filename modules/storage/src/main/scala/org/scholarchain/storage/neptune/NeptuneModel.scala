package org.scholarchain.storage.neptune

import org.scholarchain.core.{GraphEdgeLabel, GraphNodeLabel}
import org.scholarchain.storage.ports.{GraphQuery, GraphReadSettings, NodeUpsert}

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

/** The allowlisted openCypher text.
  *
  * Rules (Phase 9, A2; Neptune facts captured by the human architect on Nov 15 from AWS
  * documentation):
  *   - researcher and work IDs are always `$parameters`;
  *   - the row limit is written into the text as a literal integer (`LIMIT <limit + 1>`): SKIP and
  *     LIMIT are not supported with non-static values, and whether a parameter counts as static is
  *     not stated. The integer is the validated limit plus one, so the adapter can tell "exactly
  *     `limit` rows" from "truncated" (m1);
  *   - variable-length paths: `shortestPath()` is not supported and a property filter with a
  *     non-constant expression on a variable-length path is rejected, so the window label is
  *     written into the text as a quoted literal in the relationship pattern's property map, and
  *     only after it matched [[GraphQuery.WindowLabelPattern]] (no quote, backslash or brace can
  *     pass) and is one of the configured `graph.windows` labels. Single-hop templates keep
  *     `$windowLabel`;
  *   - the path depth comes from a validated integer; the search runs one exact-depth query per
  *     depth (iterative deepening, see `NeptuneGraphRead`).
  *
  * Nothing else derived from a caller ever reaches query text.
  */
object CypherTemplates {

  def researcherById(fetch: Int): String =
    "MATCH (r:Researcher) WHERE id(r) = $researcherId " +
      s"RETURN id(r) AS researcherId, properties(r) AS properties LIMIT $fetch"

  def coauthorNeighbors(fetch: Int): String =
    "MATCH (r:Researcher)-[e:COAUTHORED_WITH]-(n:Researcher) " +
      "WHERE id(r) = $researcherId AND e.windowLabel = $windowLabel " +
      "RETURN id(n) AS coauthorId, n.displayName AS displayName, e.weight AS weight, " +
      "e.jointPublicationCount AS jointPublicationCount, e.evidenceKey AS evidenceKey " +
      s"ORDER BY weight DESC, coauthorId ASC LIMIT $fetch"

  def coauthorEdgeBetween(fetch: Int): String =
    "MATCH (a:Researcher)-[e:COAUTHORED_WITH]-(b:Researcher) " +
      "WHERE id(a) = $a AND id(b) = $b AND e.windowLabel = $windowLabel " +
      s"RETURN id(e) AS edgeId, properties(e) AS properties LIMIT $fetch"

  /** Paths of exactly `depth` hops in one window. `windowLiteral` is a validated, configured label.
    */
  def pathAtDepth(depth: Int, windowLiteral: String, fetch: Int): String =
    s"MATCH p = (a:Researcher)-[:COAUTHORED_WITH*$depth..$depth {windowLabel: '$windowLiteral'}]-" +
      "(b:Researcher) WHERE id(a) = $from AND id(b) = $to " +
      "RETURN [n IN nodes(p) | id(n)] AS researcherIds, " +
      "[e IN relationships(p) | e.evidenceKey] AS evidenceKeys, length(p) AS hops " +
      s"LIMIT $fetch"

  def workAuthors(fetch: Int): String =
    "MATCH (r:Researcher)-[:AUTHORED]->(w:Work) WHERE id(w) = $workId " +
      "RETURN id(r) AS researcherId, r.displayName AS displayName " +
      s"ORDER BY researcherId ASC LIMIT $fetch"

  def workById(fetch: Int): String =
    "MATCH (w:Work) WHERE id(w) = $workId " +
      "RETURN id(w) AS workId, w.displayName AS displayName, w.year AS year, " +
      "w.venueKey AS venueKey, w.dblpKey AS dblpKey, w.teamSize AS teamSize " +
      s"LIMIT $fetch"

  /** MERGE upsert per allowlisted label: `$rows` is a list of `{id, props}` maps. */
  def mergeNodes(label: String): Either[String, String] =
    if (!NeptuneModel.upsertLabels.contains(label)) Left(s"label '$label' is not allowlisted")
    else
      Right(
        s"UNWIND $$rows AS row MERGE (n:$label {`~id`: row.id}) SET n += row.props " +
          "RETURN count(n) AS upserted"
      )

  /** A compiled query: template text plus its parameters as a JSON object. `limit` is the number of
    * rows the caller gets; the text asks for `limit + 1`.
    *
    * @param depth
    *   the exact path depth of one `PathBetween` step; None for single-shot templates
    */
  final case class Compiled(
      templateId: String,
      cypher: String,
      parameters: ujson.Obj,
      limit: Int,
      depth: Option[Int]
  )

  /** Validates `query` against `settings`, then picks the template text. `PathBetween` compiles to
    * one query per depth 1..maxDepth (in that order); every other template to exactly one.
    */
  def compile(query: GraphQuery, settings: GraphReadSettings): Either[String, List[Compiled]] =
    GraphQuery.validate(query, settings).map {
      case q @ GraphQuery.ResearcherById(r) =>
        List(
          Compiled(
            q.templateId,
            researcherById(q.limit + 1),
            ujson.Obj("researcherId" -> r.value),
            q.limit,
            None
          )
        )
      case q @ GraphQuery.CoauthorNeighbors(r, w, l) =>
        List(
          Compiled(
            q.templateId,
            coauthorNeighbors(l + 1),
            ujson.Obj("researcherId" -> r.value, "windowLabel" -> w),
            l,
            None
          )
        )
      case q @ GraphQuery.CoauthorEdgeBetween(a, b, w) =>
        List(
          Compiled(
            q.templateId,
            coauthorEdgeBetween(q.limit + 1),
            ujson.Obj("a" -> a.value, "b" -> b.value, "windowLabel" -> w),
            q.limit,
            None
          )
        )
      case q @ GraphQuery.PathBetween(a, b, w, d) =>
        (1 to d).toList.map { k =>
          Compiled(
            s"path_between_depth_$k",
            pathAtDepth(k, w, q.limit + 1),
            ujson.Obj("from" -> a.value, "to" -> b.value),
            q.limit,
            Some(k)
          )
        }
      case q @ GraphQuery.WorkAuthors(wk, l) =>
        List(
          Compiled(q.templateId, workAuthors(l + 1), ujson.Obj("workId" -> wk.value), l, None)
        )
      case q @ GraphQuery.WorkById(wk) =>
        List(
          Compiled(
            q.templateId,
            workById(q.limit + 1),
            ujson.Obj("workId" -> wk.value),
            q.limit,
            None
          )
        )
    }

  def mergeParameters(rows: Seq[NodeUpsert]): ujson.Obj =
    ujson.Obj("rows" -> ujson.Arr.from(rows.map { r =>
      ujson.Obj("id" -> r.id, "props" -> ujson.Obj.from(r.properties))
    }))
}
