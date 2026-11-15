package org.scholarchain.storage.neptune

import java.io.{BufferedReader, InputStreamReader}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.zip.GZIPInputStream

import scala.collection.immutable.ListMap
import scala.collection.mutable

import org.scholarchain.core._
import org.scholarchain.storage.dynamo.MetricCacheAccumulator
import org.scholarchain.storage.s3.S3KeyLayout
import org.scholarchain.storage.source._
import org.scholarchain.storage.vectors.VectorKeys

final case class NeptuneBuildSettings(
    fullWindowLabel: String,
    rowsPerFile: Int,
    gzip: Boolean,
    profileIndexName: String,
    semanticContextPrefix: String
)

/** Rows, files and bytes of one node or edge type, plus the order-independent digests of the IDs
  * read from the export, the IDs written, and the IDs read back from the CSV files.
  */
final case class TypeStats(
    stage: String,
    name: String,
    rows: Long,
    files: List[CsvFileStat],
    note: Option[String],
    inputIdDigest: Long,
    writtenIdDigest: Long,
    rereadIdDigest: Option[Long]
) {
  def bytes: Long = files.map(_.bytes).sum
  def uncompressedBytes: Long = files.map(_.uncompressedBytes).sum
  def idsMatch: Boolean =
    inputIdDigest == writtenIdDigest && rereadIdDigest.forall(_ == writtenIdDigest)
}

final case class NeptuneBuildReport(
    types: List[TypeStats],
    rejects: Long,
    provenanceSeen: List[String],
    millis: Long
)

/** Order-independent digest of a set of IDs (sum of the first 8 bytes of SHA-256, wrapping). */
final class IdDigest {
  var value = 0L
  var count = 0L
  def add(id: String): Unit = {
    val h = Hashing.sha256Hex(id)
    value += java.lang.Long.parseUnsignedLong(h.substring(0, 16), 16)
    count += 1
  }
}

/** Builds the Neptune openCypher CSV files of one export.
  *
  * Node and edge IDs are the core IDs verbatim (nodeId, edgeId, MentionId, ChunkId). Files go to
  * `<out>/nodes/<Label>/` and `<out>/edges/<TYPE>/`, matching the two bulk-load stages. Every
  * constructed record is validated with the core contract before it is written; anything that
  * fails, or an edge whose endpoint was not written, is a reject (recorded, never dropped
  * silently).
  */
final class NeptuneCsvBuilder(
    source: ExportSource,
    manifest: ExportManifest,
    out: Path,
    settings: NeptuneBuildSettings,
    rejects: RejectLog
) {
  private val Stage = "neptune-csv"
  private val written = mutable.HashSet.empty[String]
  private val stats = mutable.ListBuffer.empty[TypeStats]
  private val provenanceSeen = mutable.LinkedHashSet.empty[String]

  import CsvType._
  private def c(n: String, t: CsvType) = CsvColumn(n, t)
  private val provColumns =
    List(c("runId", Str), c("datasetId", Str), c("snapshotId", Str), c("policyVersion", Str))
  private def provValues(p: Provenance) =
    List(p.runId.value, p.datasetId, p.snapshotId, p.policyVersion)

  private def reject(table: String, what: String, reason: String): Unit =
    rejects.record(Reject(Stage, table, what, 0L, reason))

  private def fileSet(stage: String, name: String, header: List[String]) =
    new CsvFileSet(
      out,
      s"$stage/$name",
      name.toLowerCase(java.util.Locale.ROOT),
      header,
      settings.rowsPerFile,
      settings.gzip
    )

  private def finish(
      stage: String,
      name: String,
      fs: Option[CsvFileSet],
      input: IdDigest,
      out: IdDigest,
      note: Option[String]
  ): Unit = {
    fs.foreach(_.close())
    stats += TypeStats(
      stage,
      name,
      out.count,
      fs.map(_.files).getOrElse(Nil),
      note,
      input.value,
      out.value,
      None
    )
  }

  private def fmtD(d: Double): String =
    java.math.BigDecimal.valueOf(d).stripTrailingZeros().toPlainString
  private def fmtL(d: Double): String = if (d.isWhole) d.toLong.toString else fmtD(d)

  // ---- researcher metrics of the full window ------------------------------------------------

  private def fullWindowMetrics(): Map[String, MetricCacheAccumulator] = {
    val w = settings.fullWindowLabel
    val acc = mutable.HashMap.empty[String, MetricCacheAccumulator]
    def get(r: String) = acc.getOrElseUpdate(r, new MetricCacheAccumulator(r, w))
    List(
      "breadth" -> s"metrics:breadth:$w",
      "group_size" -> s"metrics:group_size:$w",
      "bridges" -> s"bridges:$w",
      "kcore" -> s"kcore:$w"
    )
      .foreach { case (family, t) =>
        TableReader.foreach[ResearcherMetrics](source, manifest.require(t), rejects, Stage)(m =>
          get(m.researcherId.value).addMetrics(family, m)
        )
      }
    List(s"components:$w", s"communities:$w").foreach { t =>
      TableReader.foreach[ResearcherGroupMembership](source, manifest.require(t), rejects, Stage)(
        g => get(g.researcherId.value).addMembership(g)
      )
    }
    acc.toMap
  }

  // ---- nodes ----------------------------------------------------------------------------------

  private sealed trait PropKind
  private case object PStr extends PropKind
  private case object PInt extends PropKind
  private case object PDouble extends PropKind

  /** Known GraphNode property keys per label and their CSV types; any other key is a reject. */
  private val knownProps: Map[GraphNodeLabel, List[(String, PropKind)]] = Map(
    GraphNodeLabel.Researcher -> List(
      "anchorMentionId" -> PStr,
      "distinctDblpOrcids" -> PStr,
      "identityOutcome" -> PStr,
      "mentionCount" -> PInt,
      "minIdentityConfidence" -> PDouble,
      "reasonCodes" -> PStr,
      "workCount" -> PInt
    ),
    GraphNodeLabel.Work -> List(
      "dblpKey" -> PStr,
      "publicationType" -> PStr,
      "teamSize" -> PInt,
      "venueKey" -> PStr,
      "year" -> PInt,
      "doi" -> PStr
    ),
    GraphNodeLabel.Venue -> Nil,
    GraphNodeLabel.Institution -> Nil,
    GraphNodeLabel.Topic -> Nil
  )

  private def propColumns(l: GraphNodeLabel) = knownProps(l).map { case (k, t) =>
    c(k, t match { case PStr => Str; case PInt => LongT; case PDouble => DoubleT })
  }

  private def propValues(n: GraphNode): Either[String, List[String]] = {
    val known = knownProps(n.label)
    val unknown = n.properties.keySet -- known.map(_._1)
    if (unknown.nonEmpty) Left(s"unknown property keys ${unknown.toList.sorted.mkString(",")}")
    else {
      val vs = known.map { case (k, t) =>
        n.properties.get(k) match {
          case None    => Right("")
          case Some(v) =>
            t match {
              case PStr    => Right(v)
              case PInt    => v.toLongOption.map(_.toString).toRight(s"$k '$v' is not an integer")
              case PDouble =>
                v.toDoubleOption
                  .filter(d => !d.isNaN && !d.isInfinite)
                  .map(fmtD)
                  .toRight(s"$k '$v' is not a number")
            }
        }
      }
      vs.collectFirst { case Left(e) => e }.toLeft(vs.collect { case Right(x) => x })
    }
  }

  private val researcherMetricColumns = List(
    c("identityConfidence", DoubleT),
    c("metricsWindowLabel", Str),
    c("metricsWindowStartYear", IntT),
    c("metricsWindowEndYear", IntT),
    c("publicationCount", LongT),
    c("distinctCoauthors", LongT),
    c("totalCoauthorOccurrences", LongT),
    c("averageCoauthorsPerPaper", DoubleT),
    c("maxCoauthorsOnOnePaper", LongT),
    c("weightedDegree", DoubleT),
    c("componentId", Str),
    c("componentSize", LongT),
    c("communityId", Str),
    c("communitySize", LongT),
    c("coreness", LongT),
    c("participationCoefficient", DoubleT)
  )

  private def researcherMetricValues(
      m: Option[MetricCacheAccumulator]
  ): Either[String, List[String]] = m match {
    case None => Left(s"no ${settings.fullWindowLabel} metrics")
    case Some(a)
        if Set("breadth", "group_size", "bridges", "kcore", "components", "communities")
          .exists(f => !a.families(f)) =>
      Left(s"incomplete ${settings.fullWindowLabel} metrics")
    case Some(a) =>
      import CollaborationMetric._
      def v(x: CollaborationMetric, f: Double => String) = a.values.get(x).map(f).getOrElse("")
      val w = a.window.get
      Right(
        List(
          a.identityConfidence.map(fmtD).getOrElse(""),
          settings.fullWindowLabel,
          w.startYear.toString,
          w.endYear.toString,
          v(PublicationCount, fmtL),
          v(DistinctCoauthors, fmtL),
          v(TotalCoauthorOccurrences, fmtL),
          v(AverageCoauthorsPerPaper, fmtD),
          v(MaxCoauthorsOnOnePaper, fmtL),
          v(WeightedDegree, fmtD),
          a.componentId.getOrElse(""),
          v(ComponentSize, fmtL),
          a.communityId.getOrElse(""),
          v(CommunitySize, fmtL),
          v(Coreness, fmtL),
          v(ParticipationCoefficient, fmtD)
        )
      )
  }

  private def graphNodeTable(
      label: GraphNodeLabel,
      metrics: Map[String, MetricCacheAccumulator]
  ): Unit = {
    val table = s"nodes:${label.wire}"
    val name = NeptuneModel.nodeLabel(label)
    val t = manifest.require(table)
    val extra = if (label == GraphNodeLabel.Researcher) researcherMetricColumns else Nil
    val header = OpenCypherCsv.nodeHeader(
      (c("displayName", Str) :: propColumns(label)) ++ extra ++ provColumns
    )
    if (t.records == 0) {
      TableReader.foreach[GraphNode](source, t, rejects, Stage)(_ => ())
      finish(
        "nodes",
        name,
        None,
        new IdDigest,
        new IdDigest,
        Some("0 rows in the export: no file written (skipped)")
      )
    } else {
      val fs = fileSet("nodes", name, header)
      val in = new IdDigest
      val outD = new IdDigest
      TableReader.foreach[GraphNode](source, t, rejects, Stage) { n =>
        in.add(n.nodeId)
        provenanceSeen += s"$table:${n.provenance.sourceType.wire}:${n.provenance.datasetId}:${n.provenance.snapshotId}"
        val row = for {
          _ <- Either.cond(n.label == label, (), s"label ${n.label.wire} in table $table")
          props <- propValues(n)
          m <-
            if (label == GraphNodeLabel.Researcher) researcherMetricValues(metrics.get(n.nodeId))
            else Right(Nil)
        } yield List(n.nodeId, name, n.displayName) ++ props ++ m ++ provValues(n.provenance)
        row match {
          case Left(e)  => reject(table, n.nodeId, e)
          case Right(r) =>
            if (written.add(n.nodeId)) { fs.write(r); outD.add(n.nodeId) }
            else reject(table, n.nodeId, "duplicate node ID")
        }
      }
      finish("nodes", name, Some(fs), in, outD, None)
    }
  }

  // ---- edges ----------------------------------------------------------------------------------

  private val windowColumns = List(c("windowStartYear", IntT), c("windowEndYear", IntT))

  private def edgeEndpointsOk(table: String, id: String, from: String, to: String): Boolean =
    if (written.contains(from) && written.contains(to)) true
    else {
      reject(table, id, s"dangling endpoint (${if (written.contains(from)) to else from})"); false
    }

  private def simpleEdgeTable(label: GraphEdgeLabel): Unit = {
    val table = s"edges:${label.wire}"
    val name = NeptuneModel.edgeType(label)
    val header =
      OpenCypherCsv.edgeHeader(windowColumns ++ List(c("weight", DoubleT)) ++ provColumns)
    val fs = fileSet("edges", name, header)
    val in = new IdDigest
    val outD = new IdDigest
    TableReader.foreach[GraphEdge](source, manifest.require(table), rejects, Stage) { e =>
      in.add(e.edgeId.value)
      if (e.label != label) reject(table, e.edgeId.value, s"label ${e.label.wire}")
      else if (edgeEndpointsOk(table, e.edgeId.value, e.from, e.to)) {
        fs.write(
          List(
            e.edgeId.value,
            e.from,
            e.to,
            name,
            e.window.startYear.toString,
            e.window.endYear.toString,
            e.weight.map(fmtD).getOrElse("")
          ) ++
            provValues(e.provenance)
        )
        outD.add(e.edgeId.value)
      }
    }
    finish("edges", name, Some(fs), in, outD, None)
  }

  private val coauthorColumns = List(
    c("windowLabel", Str),
    c("windowStartYear", IntT),
    c("windowEndYear", IntT),
    c("weight", DoubleT),
    c("jointPublicationCount", LongT),
    c("firstCollaborationYear", IntT),
    c("lastCollaborationYear", IntT),
    c("minIdentityConfidence", DoubleT),
    c("supportingWorkIds", Str),
    c("evidenceKey", Str)
  ) ++ provColumns

  private def fingerprint(from: String, to: String, w: YearWindow, weight: Double): Long =
    java.lang.Long.parseUnsignedLong(
      Hashing
        .sha256Hex(
          Hashing.canonicalEncoding(
            Seq(from, to, w.startYear.toString, w.endYear.toString, fmtD(weight))
          )
        )
        .substring(0, 16),
      16
    )

  /** coauthored_with for every window: `edges:coauthored_with` joined with `coauthor_edge` on
    * edgeId (the same ID). Endpoints, window and weight must agree; every edge carries its
    * supporting work IDs (`;`-joined, because edge properties are single-valued) and its window.
    */
  private def coauthorEdges(): Unit = {
    val name = NeptuneModel.edgeType(GraphEdgeLabel.CoauthoredWith)
    val fs = fileSet("edges", name, OpenCypherCsv.edgeHeader(coauthorColumns))
    val in = new IdDigest
    val outD = new IdDigest
    manifest.withPrefix("edges:coauthored_with:").sortBy(_.table).foreach { gt =>
      val label =
        gt.windowLabel.getOrElse(throw new ExportSourceException(s"${gt.table}: no window label"))
      val et = manifest.require(s"coauthor_edge:$label")
      val graphEdges =
        new java.util.HashMap[String, java.lang.Long](math.max(16, (gt.records * 4 / 3 + 1).toInt))
      TableReader.foreach[GraphEdge](source, gt, rejects, Stage) { e =>
        in.add(e.edgeId.value)
        if (e.label != GraphEdgeLabel.CoauthoredWith || e.weight.isEmpty)
          reject(gt.table, e.edgeId.value, "not a weighted coauthored_with edge")
        else if (
          graphEdges.put(e.edgeId.value, fingerprint(e.from, e.to, e.window, e.weight.get)) != null
        )
          reject(gt.table, e.edgeId.value, "duplicate edge ID")
      }
      TableReader.foreach[CoauthorEdge](source, et, rejects, Stage) { ce =>
        val id = ce.edgeId.value
        val fp = graphEdges.remove(id)
        if (fp == null) reject(et.table, id, "coauthor_edge without a coauthored_with graph edge")
        else if (fp.longValue != fingerprint(ce.left.value, ce.right.value, ce.window, ce.weight))
          reject(
            et.table,
            id,
            "graph edge and coauthor_edge disagree on endpoints, window or weight"
          )
        else if (ce.supportingWorkIds.isEmpty || ce.supportingWorkIds.size != ce.sharedWorkCount)
          reject(
            et.table,
            id,
            s"${ce.supportingWorkIds.size} supporting work IDs for ${ce.sharedWorkCount} joint publications"
          )
        else if (edgeEndpointsOk(et.table, id, ce.left.value, ce.right.value)) {
          fs.write(
            List(
              id,
              ce.left.value,
              ce.right.value,
              name,
              label,
              ce.window.startYear.toString,
              ce.window.endYear.toString,
              fmtD(ce.weight),
              ce.sharedWorkCount.toString,
              ce.firstYear.toString,
              ce.lastYear.toString,
              fmtD(ce.minIdentityConfidence),
              ce.supportingWorkIds.map(_.value).mkString(";"),
              id
            ) ++ provValues(ce.provenance)
          )
          outD.add(id)
        }
      }
      graphEdges.keySet.forEach(id =>
        reject(gt.table, id, "coauthored_with graph edge without coauthor_edge evidence")
      )
    }
    finish("edges", name, Some(fs), in, outD, None)
  }

  /** author_mention nodes and resolves_to edges (mention -> researcher, window = publication year,
    * with matchConfidence), from the authorship table.
    */
  private def mentions(): Unit = {
    val table = "authorship"
    val nName = NeptuneModel.nodeLabel(GraphNodeLabel.AuthorMention)
    val eName = NeptuneModel.edgeType(GraphEdgeLabel.ResolvesTo)
    val nodes = fileSet(
      "nodes",
      nName,
      OpenCypherCsv.nodeHeader(
        List(c("workId", Str), c("position", IntT), c("year", IntT)) ++ provColumns
      )
    )
    val edges = fileSet(
      "edges",
      eName,
      OpenCypherCsv.edgeHeader(windowColumns ++ List(c("matchConfidence", DoubleT)) ++ provColumns)
    )
    val nIn, nOut, eIn, eOut = new IdDigest
    TableReader.foreach[AuthorshipRecord](source, manifest.require(table), rejects, Stage) { a =>
      val mid = a.mentionId.value
      nIn.add(mid)
      val w = YearWindow(a.year, a.year)
      val node = GraphNode(1, mid, GraphNodeLabel.AuthorMention, mid, Map.empty, a.provenance)
      val edge = EdgeId.of(GraphEdgeLabel.ResolvesTo, mid, a.researcherId.value, w).map { id =>
        GraphEdge(
          1,
          id,
          GraphEdgeLabel.ResolvesTo,
          mid,
          a.researcherId.value,
          w,
          None,
          a.provenance
        )
      }
      val problems = Contract.validate(node) ++ edge.fold(List(_), e => Contract.validate(e))
      if (problems.nonEmpty) reject(table, mid, problems.mkString("; "))
      else if (!written.add(mid)) reject(table, mid, "duplicate mention ID")
      else {
        nodes.write(
          List(mid, nName, a.workId.value, a.position.toString, a.year.toString) ++ provValues(
            a.provenance
          )
        )
        nOut.add(mid)
        val e = edge.toOption.get
        eIn.add(e.edgeId.value)
        if (edgeEndpointsOk(table, e.edgeId.value, mid, a.researcherId.value)) {
          edges.write(
            List(
              e.edgeId.value,
              mid,
              a.researcherId.value,
              eName,
              a.year.toString,
              a.year.toString,
              fmtD(a.matchConfidence)
            ) ++
              provValues(a.provenance)
          )
          eOut.add(e.edgeId.value)
        }
      }
    }
    finish("nodes", nName, Some(nodes), nIn, nOut, None)
    finish("edges", eName, Some(edges), eIn, eOut, None)
  }

  /** dataset_snapshot nodes (one per distinct work provenance dataset and snapshot, nodeId
    * `<datasetId>:<snapshotId>`) and derived_from edges (work -> snapshot, window = publication
    * year).
    */
  private def snapshots(): Unit = {
    val table = "nodes:work"
    val nName = NeptuneModel.nodeLabel(GraphNodeLabel.DatasetSnapshot)
    val eName = NeptuneModel.edgeType(GraphEdgeLabel.DerivedFrom)
    val snaps = mutable.LinkedHashMap.empty[String, Provenance]
    val edges = fileSet("edges", eName, OpenCypherCsv.edgeHeader(windowColumns ++ provColumns))
    val eIn, eOut = new IdDigest
    val pendingEdges = mutable.ArrayBuffer.empty[List[String]]
    TableReader.foreach[GraphNode](source, manifest.require(table), rejects, Stage) { n =>
      val sid = s"${n.provenance.datasetId}:${n.provenance.snapshotId}"
      snaps.getOrElseUpdate(sid, n.provenance)
      n.properties.get("year").flatMap(_.toIntOption) match {
        case None    => reject(table, n.nodeId, "work without a year: no derived_from edge")
        case Some(y) =>
          val w = YearWindow(y, y)
          EdgeId
            .of(GraphEdgeLabel.DerivedFrom, n.nodeId, sid, w)
            .map(id =>
              GraphEdge(1, id, GraphEdgeLabel.DerivedFrom, n.nodeId, sid, w, None, n.provenance)
            ) match {
            case Left(e)  => reject(table, n.nodeId, e)
            case Right(e) =>
              Contract.validate(e) match {
                case Nil =>
                  eIn.add(e.edgeId.value)
                  pendingEdges += (List(
                    e.edgeId.value,
                    n.nodeId,
                    sid,
                    eName,
                    y.toString,
                    y.toString
                  ) ++ provValues(n.provenance))
                case errs => reject(table, n.nodeId, errs.mkString("; "))
              }
          }
      }
    }
    val nodes = fileSet(
      "nodes",
      nName,
      OpenCypherCsv.nodeHeader(
        List(
          c("displayName", Str),
          c("sourceDatasetId", Str),
          c("sourceSnapshotId", Str)
        ) ++ provColumns
      )
    )
    val nIn, nOut = new IdDigest
    snaps.foreach { case (sid, p) =>
      nIn.add(sid)
      Contract.validate(
        GraphNode(1, sid, GraphNodeLabel.DatasetSnapshot, sid, Map.empty, p)
      ) match {
        case Nil =>
          written.add(sid)
          nodes.write(List(sid, nName, sid, p.datasetId, p.snapshotId) ++ provValues(p))
          nOut.add(sid)
        case errs => reject(table, sid, errs.mkString("; "))
      }
    }
    pendingEdges.foreach { r =>
      if (edgeEndpointsOk(table, r.head, r(1), r(2))) { edges.write(r); eOut.add(r.head) }
    }
    finish("nodes", nName, Some(nodes), nIn, nOut, None)
    finish("edges", eName, Some(edges), eIn, eOut, None)
  }

  /** text_chunk nodes and supported_by edges (researcher -> text_chunk) for researcher-profile
    * documents. The chunk ID, vector key and text key are computed with the same rules as the
    * ingest pipeline (ChunkId.forSubjects ordinal 0, VectorKeys, S3KeyLayout.textKey).
    */
  private def textChunks(): Unit = {
    val nName = NeptuneModel.nodeLabel(GraphNodeLabel.TextChunk)
    val eName = NeptuneModel.edgeType(GraphEdgeLabel.SupportedBy)
    val nodes = fileSet(
      "nodes",
      nName,
      OpenCypherCsv.nodeHeader(
        List(
          c("documentType", Str),
          c("indexName", Str),
          c("vectorKey", Str),
          c("textKey", Str),
          c("textHash", Str),
          c("windowLabel", Str),
          c("sourceEvidenceId", Str)
        ) ++ provColumns
      )
    )
    val edges = fileSet("edges", eName, OpenCypherCsv.edgeHeader(windowColumns ++ provColumns))
    val nIn, nOut, eIn, eOut = new IdDigest
    manifest.withPrefix("semantic_summary:researcher_profile:").sortBy(_.table).foreach { t =>
      val label =
        t.windowLabel.getOrElse(throw new ExportSourceException(s"${t.table}: no window label"))
      TableReader.foreach[EvidenceItem](source, t, rejects, Stage) { e =>
        val built = for {
          hash <- e.provenance.contentHash.toRight("summary without a content hash")
          w <- e.window.toRight("summary without a window")
          rid <- e.subjectIds match {
            case List(r) => Right(r); case _ => Left("profile must have one subject")
          }
          chunk <- ChunkId.forSubjects(
            SemanticDocumentType.ResearcherProfile,
            e.subjectIds,
            Some(label),
            0,
            hash
          )
          vkey <- VectorKeys.derive(
            SemanticDocumentType.ResearcherProfile,
            Some(label),
            e.subjectIds,
            0
          )
          edgeId <- EdgeId.of(GraphEdgeLabel.SupportedBy, rid, chunk.value, w)
          node = GraphNode(1, chunk.value, GraphNodeLabel.TextChunk, vkey, Map.empty, e.provenance)
          edge = GraphEdge(
            1,
            edgeId,
            GraphEdgeLabel.SupportedBy,
            rid,
            chunk.value,
            w,
            None,
            e.provenance
          )
          _ <- (Contract.validate(node) ++ Contract.validate(edge)) match {
            case Nil  => Right(())
            case errs => Left(errs.mkString("; "))
          }
        } yield (chunk.value, rid, vkey, hash, w, edgeId.value)
        built match {
          case Left(err)                             => reject(t.table, e.evidenceId.value, err)
          case Right((cid, rid, vkey, hash, w, eid)) =>
            nIn.add(cid)
            if (!written.add(cid)) reject(t.table, cid, "duplicate chunk ID")
            else {
              nodes.write(
                List(
                  cid,
                  nName,
                  SemanticDocumentType.ResearcherProfile.wire,
                  settings.profileIndexName,
                  vkey,
                  S3KeyLayout
                    .textKey(settings.semanticContextPrefix, settings.profileIndexName, hash),
                  hash,
                  label,
                  e.evidenceId.value
                ) ++ provValues(e.provenance)
              )
              nOut.add(cid)
              eIn.add(eid)
              if (edgeEndpointsOk(t.table, eid, rid, cid)) {
                edges.write(
                  List(
                    eid,
                    rid,
                    cid,
                    eName,
                    w.startYear.toString,
                    w.endYear.toString
                  ) ++ provValues(e.provenance)
                )
                eOut.add(eid)
              }
            }
        }
      }
    }
    finish("nodes", nName, Some(nodes), nIn, nOut, None)
    finish("edges", eName, Some(edges), eIn, eOut, None)
  }

  /** Builds every file, then re-reads them to check the written IDs. */
  def build(): NeptuneBuildReport = {
    val t0 = System.nanoTime()
    val metrics = fullWindowMetrics()
    List(
      GraphNodeLabel.Researcher,
      GraphNodeLabel.Work,
      GraphNodeLabel.Venue,
      GraphNodeLabel.Institution,
      GraphNodeLabel.Topic
    )
      .foreach(l => graphNodeTable(l, metrics))
    mentions()
    snapshots()
    textChunks()
    simpleEdgeTable(GraphEdgeLabel.Authored)
    simpleEdgeTable(GraphEdgeLabel.PublishedIn)
    coauthorEdges()
    List(GraphEdgeLabel.AffiliatedWith, GraphEdgeLabel.HasTopic).foreach { l =>
      stats += TypeStats(
        "edges",
        NeptuneModel.edgeType(l),
        0,
        Nil,
        Some("no input in this export (no enrichment data): no file written (skipped)"),
        0L,
        0L,
        None
      )
    }
    val reread = stats.toList.map(s =>
      s.copy(rereadIdDigest = Some(NeptuneCsvBuilder.rereadDigest(out, s.files)))
    )
    NeptuneBuildReport(
      reread,
      rejects.byStageAndTable.filter(_._1._1 == Stage).map(_._2).sum,
      provenanceSeen.toList,
      (System.nanoTime() - t0) / 1000000L
    )
  }
}

object NeptuneCsvBuilder {

  /** Digest of the `:ID` column of the given files. */
  def rereadDigest(root: Path, files: List[CsvFileStat]): Long = {
    val d = new IdDigest
    files.foreach { f =>
      val p = root.resolve(f.relativePath)
      val raw = Files.newInputStream(p)
      val in = if (f.relativePath.endsWith(".gz")) new GZIPInputStream(raw, 1 << 16) else raw
      val r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 16)
      try {
        val header = OpenCypherCsv.parseLine(r.readLine())
        val idCol = header.indexOf(":ID")
        var line = r.readLine()
        while (line != null) {
          d.add(OpenCypherCsv.parseLine(line)(idCol))
          line = r.readLine()
        }
      } finally r.close()
    }
    d.value
  }

  def toJson(r: NeptuneBuildReport): ujson.Obj = ujson.Obj(
    "rejects" -> r.rejects.toDouble,
    "millis" -> r.millis.toDouble,
    "provenanceSeen" -> ujson.Arr.from(r.provenanceSeen.map(ujson.Str(_))),
    "types" -> ujson.Arr.from(r.types.map { t =>
      ujson.Obj(
        "stage" -> t.stage,
        "type" -> t.name,
        "rows" -> t.rows.toDouble,
        "files" -> t.files.size,
        "bytes" -> t.bytes.toDouble,
        "uncompressedBytes" -> t.uncompressedBytes.toDouble,
        "note" -> t.note.map(ujson.Str(_)).getOrElse(ujson.Null),
        "idsMatch" -> t.idsMatch,
        "inputIdDigest" -> java.lang.Long.toUnsignedString(t.inputIdDigest),
        "writtenIdDigest" -> java.lang.Long.toUnsignedString(t.writtenIdDigest),
        "rereadIdDigest" -> t.rereadIdDigest
          .map(x => ujson.Str(java.lang.Long.toUnsignedString(x)))
          .getOrElse(ujson.Null),
        "fileList" -> ujson.Arr.from(
          t.files.map(f =>
            ujson.Obj(
              "path" -> f.relativePath,
              "rows" -> f.rows.toDouble,
              "bytes" -> f.bytes.toDouble,
              "sha256" -> f.sha256
            )
          )
        )
      )
    })
  )
}
