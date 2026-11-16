package org.scholarchain.storage

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.collection.mutable

import org.scholarchain.core._
import org.scholarchain.core.config.{ConfigLoader, ScholarChainConfig}
import upickle.default.ReadWriter

/** Test configuration (placeholder names only) and a small synthetic Spark JSONL export with a
  * valid `_export_manifest.json`: 4 researchers on a coauthor chain A-B-C-D over 3 works.
  */
object TestFixtures {

  lazy val config: ScholarChainConfig = {
    val in = getClass.getResourceAsStream("/config/storage-local-v1.json")
    val text = try new String(in.readAllBytes(), StandardCharsets.UTF_8)
    finally in.close()
    ConfigLoader
      .load(text, "storage-local-v1.json", Map.empty)
      .fold(e => sys.error(e.message), _.config)
  }
  lazy val storageConfig: StorageConfig =
    StorageConfig.from(config).fold(e => sys.error(e.mkString), identity)

  /** The `runtime` section added to the test configuration (validated by ConfigLoader). */
  val runtimeSection: ujson.Obj = ujson.Obj(
    "defaultWindowLabel" -> "full-2015-2025",
    "rankingCacheTopK" -> 3,
    "maxBundleItems" -> 5,
    "maxPathDepth" -> 4,
    "graphResultLimit" -> 50,
    "graphTimeoutMillis" -> 1000,
    "maxResearcherNames" -> 5
  )

  lazy val runtimeConfig: ScholarChainConfig = {
    val in = getClass.getResourceAsStream("/config/storage-local-v1.json")
    val tree =
      try ujson.read(new String(in.readAllBytes(), StandardCharsets.UTF_8))
      finally in.close()
    tree("runtime") = runtimeSection
    ConfigLoader
      .load(tree.render(indent = 2), "storage-runtime-v1.json", Map.empty)
      .fold(e => sys.error(e.message), _.config)
  }
  lazy val runtimeStorageConfig: StorageConfig =
    StorageConfig.from(runtimeConfig).fold(e => sys.error(e.mkString), identity)

  /** Graph bounds of the runtime test configuration. */
  lazy val graphSettings: org.scholarchain.storage.ports.GraphReadSettings =
    org.scholarchain.storage.ports.GraphReadSettings.from(runtimeStorageConfig).toOption.get

  val Full = "full-2015-2025"
  val FullWindow: YearWindow = YearWindow(2015, 2025)

  val sparkRun: RunId = RunId.of("spark.test", "code", "input", "config").toOption.get

  def prov(ids: List[String], hash: Option[String] = None): Provenance =
    Provenance(
      1,
      SourceType.Pipeline,
      "scholarchain-graph",
      "dblp-2026-11-01",
      ids,
      sparkRun,
      "spark.test",
      "graph-v1",
      hash
    )

  val w1: WorkId = WorkId.fromDblpKey("conf/x/A16")
  val w2: WorkId = WorkId.fromDblpKey("conf/x/B19")
  val w3: WorkId = WorkId.fromDblpKey("conf/x/C22")
  val works: List[(WorkId, String, Int)] =
    List((w1, "conf/x/A16", 2016), (w2, "conf/x/B19", 2019), (w3, "conf/x/C22", 2022))

  val rA: ResearcherId = ResearcherId.fromAnchorMention(MentionId.of(w1, 0))
  val rB: ResearcherId = ResearcherId.fromAnchorMention(MentionId.of(w1, 1))
  val rC: ResearcherId = ResearcherId.fromAnchorMention(MentionId.of(w2, 1))
  val rD: ResearcherId = ResearcherId.fromAnchorMention(MentionId.of(w3, 1))
  val names: Map[ResearcherId, String] =
    Map(rA -> "Alice Smith", rB -> "Bob Jones", rC -> "Carol King", rD -> "Dan Brown")
  val researchers: List[ResearcherId] = List(rA, rB, rC, rD)

  /** (researcher, work, position, year). */
  val authorships: List[(ResearcherId, WorkId, Int, Int)] =
    List(
      (rA, w1, 0, 2016),
      (rB, w1, 1, 2016),
      (rB, w2, 0, 2019),
      (rC, w2, 1, 2019),
      (rC, w3, 0, 2022),
      (rD, w3, 1, 2022)
    )

  /** Coauthor pairs: (a, b, supporting work). */
  val pairs: List[(ResearcherId, ResearcherId, WorkId, Int)] =
    List((rA, rB, w1, 2016), (rB, rC, w2, 2019), (rC, rD, w3, 2022))

  def coauthorEdges: List[CoauthorEdge] = pairs.map { case (a, b, w, y) =>
    val (l, r) = UndirectedPair.canonical(a.value, b.value).toOption.get
    val id = EdgeId.coauthor(a, b, FullWindow).toOption.get
    CoauthorEdge(
      1,
      id,
      ResearcherId(l),
      ResearcherId(r),
      FullWindow,
      1.0,
      1,
      y,
      y,
      List(w),
      0.95,
      prov(List(w.value))
    )
  }

  def graphCoauthorEdges: List[GraphEdge] = coauthorEdges.map(e =>
    GraphEdge(
      1,
      e.edgeId,
      GraphEdgeLabel.CoauthoredWith,
      e.left.value,
      e.right.value,
      e.window,
      Some(e.weight),
      e.provenance
    )
  )

  def researcherNodes: List[GraphNode] = researchers.map(r =>
    GraphNode(
      1,
      r.value,
      GraphNodeLabel.Researcher,
      names(r),
      Map(
        "identityOutcome" -> "accepted",
        "mentionCount" -> "2",
        "workCount" -> "2",
        "minIdentityConfidence" -> "0.95"
      ),
      prov(List(r.value))
    )
  )

  def workNodes: List[GraphNode] = works.map { case (w, key, y) =>
    GraphNode(
      1,
      w.value,
      GraphNodeLabel.Work,
      s"A paper, with a comma and \"quotes\" ($key)",
      Map(
        "dblpKey" -> key,
        "publicationType" -> "inproceedings",
        "teamSize" -> "2",
        "venueKey" -> "conf/x",
        "year" -> y.toString
      ),
      prov(List(key))
    )
  }

  def venueNodes: List[GraphNode] = List(
    GraphNode(1, "conf/x", GraphNodeLabel.Venue, "X Conference", Map.empty, prov(List("conf/x")))
  )

  def authoredEdges: List[GraphEdge] =
    authorships.map { case (r, w, _, y) =>
      val win = YearWindow(y, y)
      GraphEdge(
        1,
        EdgeId.of(GraphEdgeLabel.Authored, r.value, w.value, win).toOption.get,
        GraphEdgeLabel.Authored,
        r.value,
        w.value,
        win,
        None,
        prov(List(w.value))
      )
    }

  def publishedInEdges: List[GraphEdge] = works.map { case (w, _, y) =>
    val win = YearWindow(y, y)
    GraphEdge(
      1,
      EdgeId.of(GraphEdgeLabel.PublishedIn, w.value, "conf/x", win).toOption.get,
      GraphEdgeLabel.PublishedIn,
      w.value,
      "conf/x",
      win,
      None,
      prov(List(w.value))
    )
  }

  def authorshipRecords: List[AuthorshipRecord] = authorships.map { case (r, w, p, y) =>
    AuthorshipRecord(1, r, w, MentionId.of(w, p), p, y, 0.95, prov(List(w.value)))
  }

  def canonical: List[CanonicalResearcher] = authorships
    .groupBy(_._1)
    .toList
    .sortBy(_._1.value)
    .map { case (r, as) =>
      val (_, w, p, _) = as.minBy(a => (a._4, a._2.value, a._3))
      val ext =
        if (r == rA)
          List(
            SourcedExternalId(
              ExternalIdType.Orcid,
              "0000-0002-1825-0097",
              SourceType.Dblp,
              "dblp",
              "2026-11-01",
              "conf/x/A16"
            )
          )
        else Nil
      CanonicalResearcher(
        1,
        r,
        MentionId.of(w, p),
        names(r),
        List(names(r)),
        ext,
        prov(List(r.value))
      )
    }

  private def degree(r: ResearcherId) = pairs.count(p => p._1 == r || p._2 == r)

  def metrics(family: String): List[ResearcherMetrics] = researchers.map { r =>
    val n = authorships.count(_._1 == r).toDouble
    val c = degree(r).toDouble
    val values = family match {
      case "breadth" =>
        List(
          MetricValue(CollaborationMetric.PublicationCount, n),
          MetricValue(CollaborationMetric.DistinctCoauthors, c),
          MetricValue(CollaborationMetric.TotalCoauthorOccurrences, c),
          MetricValue(CollaborationMetric.AverageCoauthorsPerPaper, c / n),
          MetricValue(CollaborationMetric.MaxCoauthorsOnOnePaper, 1),
          MetricValue(CollaborationMetric.WeightedDegree, c)
        )
      case "group_size" =>
        List(
          MetricValue(CollaborationMetric.ComponentSize, 4),
          MetricValue(CollaborationMetric.CommunitySize, 4)
        )
      case "bridges" =>
        List(
          MetricValue(CollaborationMetric.ParticipationCoefficient, 0.0),
          MetricValue(CollaborationMetric.CrossCommunityDegree, 0),
          MetricValue(CollaborationMetric.CommunitiesConnected, 0)
        )
      case "kcore" => List(MetricValue(CollaborationMetric.Coreness, 1))
    }
    ResearcherMetrics(1, r, FullWindow, values, 0.95, prov(List(r.value)))
  }

  def memberships(kind: GroupKind): List[ResearcherGroupMembership] = {
    val smallest = researchers.map(_.value).min(CodePointOrder.ordering)
    researchers.map(r =>
      ResearcherGroupMembership(
        1,
        r,
        FullWindow,
        kind,
        ResearcherId(if (kind == GroupKind.Component) smallest else rA.value),
        4,
        prov(List(r.value))
      )
    )
  }

  def metricEvidence: List[EvidenceItem] = researchers.map { r =>
    val ws = authorships.filter(_._1 == r).map(_._2.value).sorted(CodePointOrder.ordering)
    val subjects = r.value :: ws
    EvidenceItem(
      1,
      EvidenceId.of(EvidenceType.Metric, sparkRun, subjects),
      EvidenceType.Metric,
      subjects,
      Some(FullWindow),
      Some(MetricValue(CollaborationMetric.MaxCoauthorsOnOnePaper, 1)),
      None,
      prov(ws)
    )
  }

  val pathSubjects: List[String] = List(rA, rB, rC, rD).map(_.value)

  def pathEvidence: List[EvidenceItem] = List(
    EvidenceItem(
      1,
      EvidenceId.of(EvidenceType.GraphPath, sparkRun, pathSubjects),
      EvidenceType.GraphPath,
      pathSubjects,
      Some(FullWindow),
      None,
      None,
      prov(pathSubjects)
    )
  )

  def summary(
      t: EvidenceType,
      subjects: List[String],
      text: String,
      metric: Option[MetricValue]
  ): EvidenceItem =
    EvidenceItem(
      1,
      EvidenceId.of(t, sparkRun, subjects),
      t,
      subjects,
      Some(FullWindow),
      metric,
      Some(text),
      prov(subjects, Some(ContentHash.of(text)))
    )

  def profileText(r: ResearcherId): String =
    s"Researcher ${names(r)} (${r.value}), window 2015-2025. Publications P=2."

  def profileSummaries: List[EvidenceItem] = researchers.map(r =>
    summary(
      EvidenceType.Metric,
      List(r.value),
      profileText(r),
      Some(MetricValue(CollaborationMetric.PublicationCount, 2))
    )
  )
  def communitySummaries: List[EvidenceItem] = List(
    summary(
      EvidenceType.Metric,
      List(rA.value, rB.value, rA.value),
      s"Community ${rA.value}, window 2015-2025: 4 researchers.",
      Some(MetricValue(CollaborationMetric.CommunitySize, 4))
    )
  )
  def pathSummaries: List[EvidenceItem] = List(
    summary(
      EvidenceType.GraphPath,
      pathSubjects,
      "Collaboration path in window 2015-2025 from Alice Smith to Dan Brown with 3 hops.",
      None
    )
  )

  final case class Table(
      name: String,
      path: String,
      coreRecord: Option[String],
      window: Option[String],
      lines: List[String]
  )

  private def enc[A: ReadWriter: Validator](xs: List[A]): List[String] =
    xs.map(x => Contract.encode(x).fold(e => sys.error(e.message), identity))

  def tables: List[Table] = List(
    Table(
      "nodes:researcher",
      "nodes/node_label=researcher",
      Some("GraphNode"),
      None,
      enc(researcherNodes)
    ),
    Table("nodes:work", "nodes/node_label=work", Some("GraphNode"), None, enc(workNodes)),
    Table("nodes:venue", "nodes/node_label=venue", Some("GraphNode"), None, enc(venueNodes)),
    Table("nodes:institution", "nodes/node_label=institution", Some("GraphNode"), None, Nil),
    Table("nodes:topic", "nodes/node_label=topic", Some("GraphNode"), None, Nil),
    Table(
      "edges:authored",
      "edges/edge_label=authored",
      Some("GraphEdge"),
      None,
      enc(authoredEdges)
    ),
    Table(
      "edges:published_in",
      "edges/edge_label=published_in",
      Some("GraphEdge"),
      None,
      enc(publishedInEdges)
    ),
    Table(
      s"edges:coauthored_with:$Full",
      s"edges/edge_label=coauthored_with/window_label=$Full",
      Some("GraphEdge"),
      Some(Full),
      enc(graphCoauthorEdges)
    ),
    Table(
      "canonical_researcher",
      "canonical_researcher",
      Some("CanonicalResearcher"),
      None,
      enc(canonical)
    ),
    Table("authorship", "authorship", Some("AuthorshipRecord"), None, enc(authorshipRecords)),
    Table(
      s"coauthor_edge:$Full",
      s"coauthor_edge/window_label=$Full",
      Some("CoauthorEdge"),
      Some(Full),
      enc(coauthorEdges)
    ),
    Table(
      s"metrics:breadth:$Full",
      s"metrics/metric_type=breadth/window_label=$Full",
      Some("ResearcherMetrics"),
      Some(Full),
      enc(metrics("breadth"))
    ),
    Table(
      s"metrics:group_size:$Full",
      s"metrics/metric_type=group_size/window_label=$Full",
      Some("ResearcherMetrics"),
      Some(Full),
      enc(metrics("group_size"))
    ),
    Table(
      s"bridges:$Full",
      s"bridges/window_label=$Full",
      Some("ResearcherMetrics"),
      Some(Full),
      enc(metrics("bridges"))
    ),
    Table(
      s"kcore:$Full",
      s"kcore/window_label=$Full",
      Some("ResearcherMetrics"),
      Some(Full),
      enc(metrics("kcore"))
    ),
    Table(
      s"components:$Full",
      s"components/window_label=$Full",
      Some("ResearcherGroupMembership"),
      Some(Full),
      enc(memberships(GroupKind.Component))
    ),
    Table(
      s"communities:$Full",
      s"communities/window_label=$Full",
      Some("ResearcherGroupMembership"),
      Some(Full),
      enc(memberships(GroupKind.Community))
    ),
    Table(
      s"evidence:metric:$Full",
      s"evidence/evidence_type=metric/window_label=$Full",
      Some("EvidenceItem"),
      Some(Full),
      enc(metricEvidence)
    ),
    Table(
      s"path_evidence:$Full",
      s"path_evidence/window_label=$Full",
      Some("EvidenceItem"),
      Some(Full),
      enc(pathEvidence)
    ),
    Table(
      s"semantic_summary:researcher_profile:$Full",
      s"semantic_summary/summary_kind=researcher_profile/window_label=$Full",
      Some("EvidenceItem"),
      Some(Full),
      enc(profileSummaries)
    ),
    Table(
      s"semantic_summary:community:$Full",
      s"semantic_summary/summary_kind=community/window_label=$Full",
      Some("EvidenceItem"),
      Some(Full),
      enc(communitySummaries)
    ),
    Table(
      s"semantic_summary:path:$Full",
      s"semantic_summary/summary_kind=path/window_label=$Full",
      Some("EvidenceItem"),
      Some(Full),
      enc(pathSummaries)
    )
  )

  /** Writes the export under `root/run=<hex>/` and returns that directory. `edit` may replace a
    * table's lines (to inject bad data); the manifest always lists the written files truthfully
    * unless `manifestRecords` overrides a table's record count.
    */
  def writeExport(
      root: Path,
      edit: Table => List[String] = _.lines,
      manifestRecords: Map[String, Long] = Map.empty
  ): Path = {
    val dir = root.resolve("run=" + sparkRun.value.stripPrefix(RunId.Prefix))
    Files.createDirectories(dir)
    val entries = mutable.ListBuffer.empty[ujson.Obj]
    tables.foreach { t =>
      val lines = edit(t)
      val rel = s"${t.path}/part-00000.jsonl"
      val bytes = lines.map(_ + "\n").mkString.getBytes(StandardCharsets.UTF_8)
      val p = dir.resolve(rel)
      Files.createDirectories(p.getParent)
      Files.write(p, bytes)
      val records = manifestRecords.getOrElse(t.name, lines.size.toLong)
      entries += ujson.Obj(
        "table" -> t.name,
        "path" -> t.path,
        "coreRecord" -> t.coreRecord.map(ujson.Str(_)).getOrElse(ujson.Null),
        "schemaVersion" -> t.coreRecord.map(_ => ujson.Num(1)).getOrElse(ujson.Null),
        "windowLabel" -> t.window.map(ujson.Str(_)).getOrElse(ujson.Null),
        "records" -> records.toDouble,
        "files" -> ujson.Arr(
          ujson.Obj(
            "path" -> rel,
            "bytes" -> bytes.length.toDouble,
            "sha256" -> Hashing.sha256Hex(bytes),
            "records" -> records.toDouble
          )
        )
      )
    }
    val manifest = ujson.Obj(
      "format" -> "scholarchain.spark.jsonl-export",
      "formatVersion" -> 1,
      "sourceRunId" -> sparkRun.value,
      "runId" -> sparkRun.value,
      "tables" -> ujson.Arr.from(entries)
    )
    Files.write(
      dir.resolve("_export_manifest.json"),
      manifest.render(indent = 2).getBytes(StandardCharsets.UTF_8)
    )
    dir
  }

  /** A fresh directory under the JVM temporary directory (tests never reuse a directory). */
  def tempDir(prefix: String): Path = Files.createTempDirectory(s"scholarchain-storage-$prefix")
}
