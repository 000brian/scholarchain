package org.scholarchain.storage.dynamo

import java.util.concurrent.{Executors, Semaphore, TimeUnit}
import java.util.concurrent.atomic.AtomicLong

import scala.collection.immutable.ListMap
import scala.collection.mutable

import org.scholarchain.core._
import org.scholarchain.core.config.DynamoDbSettings
import org.scholarchain.storage.ports.{AttrValue, Item, KeyValueStore}
import org.scholarchain.storage.source._

/** Load statistics of one table in one pass. */
final class TableLoadStats(val table: String) {
  val items = new AtomicLong()
  val inserted = new AtomicLong()
  val updated = new AtomicLong()
  val unchanged = new AtomicLong()
  val conflicts = new AtomicLong()
  val rejected = new AtomicLong()
  val writeUnits = new AtomicLong()
  def changes: Long = inserted.get + updated.get
  def count(c: Change): Unit = c match {
    case Change.Inserted  => inserted.incrementAndGet()
    case Change.Updated   => updated.incrementAndGet()
    case Change.Unchanged => unchanged.incrementAndGet()
    case Change.Conflict  => conflicts.incrementAndGet()
  }
  def toJson: ujson.Obj = ujson.Obj(
    "items" -> items.get.toDouble,
    "inserted" -> inserted.get.toDouble,
    "updated" -> updated.get.toDouble,
    "unchanged" -> unchanged.get.toDouble,
    "conflicts" -> conflicts.get.toDouble,
    "rejected" -> rejected.get.toDouble,
    "changes" -> changes.toDouble,
    "writeUnitsIfWritten" -> writeUnits.get.toDouble
  )
}

/** Size distribution of the items of one table (bytes, DynamoDB sizing rules). */
final case class SizeDistribution(
    count: Long,
    min: Int,
    p50: Int,
    p90: Int,
    p99: Int,
    max: Int,
    over1Kb: Long,
    buckets: ListMap[String, Long]
) {
  def toJson: ujson.Obj = ujson.Obj(
    "count" -> count.toDouble,
    "minBytes" -> min,
    "p50Bytes" -> p50,
    "p90Bytes" -> p90,
    "p99Bytes" -> p99,
    "maxBytes" -> max,
    "over1KB" -> over1Kb.toDouble,
    "buckets" -> ujson.Obj.from(buckets.map { case (k, v) => k -> ujson.Num(v.toDouble) })
  )
}

object SizeDistribution {
  private val Edges = List(256, 512, 768, 1024, 2048, 4096)

  def of(sizes: Array[Int]): SizeDistribution = {
    val s = sizes.sorted
    def q(p: Double) =
      if (s.isEmpty) 0 else s(math.min(s.length - 1, math.ceil(p * s.length).toInt - 1).max(0))
    val buckets = ListMap.from(
      Edges.zipWithIndex.map { case (e, i) =>
        val lo = if (i == 0) 0 else Edges(i - 1)
        s"${lo + 1}-$e" -> s.count(x => x > lo && x <= e).toLong
      } :+ (s">${Edges.last}" -> s.count(_ > Edges.last).toLong)
    )
    SizeDistribution(
      s.length.toLong,
      s.headOption.getOrElse(0),
      q(0.5),
      q(0.9),
      q(0.99),
      s.lastOption.getOrElse(0),
      s.count(_ > 1024).toLong,
      buckets
    )
  }
}

final case class DynamoLoadReport(
    pass: Int,
    runId: RunId,
    ledgerAttempt: Int,
    tables: ListMap[String, TableLoadStats],
    metricItemSizes: SizeDistribution,
    entityLinkNamesSkipped: Long,
    millis: Long
) {
  def dataChanges: Long = tables.values.map(_.changes).sum
  def toJson: ujson.Obj = ujson.Obj(
    "pass" -> pass,
    "runId" -> runId.value,
    "ledgerAttempt" -> ledgerAttempt,
    "dataChanges" -> dataChanges.toDouble,
    "tables" -> ujson.Obj.from(tables.map { case (k, v) => k -> v.toJson }),
    "researcherMetricCacheItemSizes" -> metricItemSizes.toJson,
    "entityLinkNamesSkipped" -> entityLinkNamesSkipped.toDouble,
    "millis" -> millis.toDouble
  )
}

/** Builds the DynamoDB items of one export and applies them with conditional writes.
  *
  * Tables loaded from the export:
  *   - ResearcherMetricCache: one item per (researcherId, window), merging breadth, group size,
  *     bridges, k-core, component and community IDs, the M_r work IDs, identityConfidence, runId;
  *   - EvidenceBundle: the path evidence items, keyed by (evidenceId, window);
  *   - EntityLinkCache: name and ORCID links of canonical researchers;
  *   - ArtifactRegistry: the Neptune CSV files given (`neptune-csv` artifacts, sha256 and rows).
  *
  * QuerySession is runtime-only (0 items). RunLedger gets one claim and one summary event per pass
  * (append-only audit). Coauthor edges are not loaded into DynamoDB (Neptune and S3 hold them).
  *
  * Ranking items (RM4, Phase 9), when `rankingTopK` is set: the top `rankingTopK` researchers of
  * every window for each metric of [[RankingMetrics.ranked]], built from the same
  * [[MetricCacheAccumulator]]s as the RM1 items (only researchers whose RM1 item was built, and
  * only those that have the metric value), ordered by [[RankingOrder]]. Display names come from
  * `canonical_researcher` (read once, during the EntityLinkCache pass); a ranked researcher without
  * a canonical record is rejected, never defaulted. Written upsert-if-changed and counted under
  * `ResearcherMetricCacheRanking`.
  *
  * `parallelism` bounds concurrent conditional writes (1 = sequential, the local default).
  */
final class DynamoLoader(
    source: ExportSource,
    manifest: ExportManifest,
    kv: KeyValueStore,
    tables: DynamoDbSettings,
    rejects: RejectLog,
    parallelism: Int,
    rankingTopK: Option[Int]
) {
  require(parallelism >= 1 && parallelism <= 64, "parallelism must be in 1..64")
  require(
    rankingTopK.forall(k =>
      k >= 1 && k <= org.scholarchain.core.config.RuntimeSettings.MaxRankingCacheTopK
    ),
    "rankingTopK must be in 1..500"
  )
  private val Stage = "dynamodb-load"
  private val writer = new ConditionalWriter(kv)

  private final class Writes(stats: TableLoadStats) {
    private val pool =
      if (parallelism > 1) Some(Executors.newFixedThreadPool(parallelism)) else None
    private val permits = new Semaphore(parallelism * 4)
    def submit(f: => Change): Unit = pool match {
      case None    => stats.count(f)
      case Some(p) =>
        permits.acquire()
        p.execute { () =>
          try stats.count(f)
          catch {
            case e: Throwable =>
              stats.conflicts.incrementAndGet();
              rejects.record(Reject(Stage, stats.table, "-", 0, String.valueOf(e.getMessage)))
          } finally permits.release()
        }
    }
    def close(): Unit = pool.foreach { p => p.shutdown(); p.awaitTermination(1, TimeUnit.HOURS) }
  }

  private def metricCache(
      stats: TableLoadStats,
      sizes: mutable.ArrayBuilder.ofInt
  ): List[RankingAccumulator] = {
    val table = tables.researcherMetricCacheTable
    val windows = manifest.withPrefix("metrics:breadth:").flatMap(_.windowLabel).sorted
    windows.flatMap { w =>
      val ranking = rankingTopK.map(new RankingAccumulator(w, _))
      val acc = mutable.LinkedHashMap.empty[String, MetricCacheAccumulator]
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
      TableReader
        .foreach[EvidenceItem](source, manifest.require(s"evidence:metric:$w"), rejects, Stage) {
          e =>
            e.subjectIds.headOption match {
              case Some(r) if e.evidenceType == EvidenceType.Metric => get(r).addMetricEvidence(e)
              case _                                                =>
                rejects.record(
                  Reject(
                    Stage,
                    s"evidence:metric:$w",
                    e.evidenceId.value,
                    0,
                    "not a metric evidence item"
                  )
                )
            }
        }
      val ws = new Writes(stats)
      acc.valuesIterator.foreach { a =>
        a.toItem match {
          case Left(err) =>
            stats.rejected.incrementAndGet()
            rejects.record(Reject(Stage, "ResearcherMetricCache", s"${a.researcherId}/$w", 0, err))
          case Right(item) =>
            stats.items.incrementAndGet()
            val stamped = ItemHash.stamp(item, 1L)
            sizes += ItemSize.of(stamped)
            stats.writeUnits.addAndGet(ItemSize.writeUnits(stamped).toLong)
            ws.submit(writer.upsertIfChanged(table, item))
            ranking.foreach(_.offer(a))
        }
      }
      ws.close()
      ranking.toList
    }
  }

  private def rankingItems(
      stats: TableLoadStats,
      rankings: List[RankingAccumulator],
      names: collection.Map[String, String]
  ): Unit = {
    val table = tables.researcherMetricCacheTable
    val ws = new Writes(stats)
    rankings.foreach { r =>
      r.rankings.foreach { case (metric, entries) =>
        entries.zipWithIndex.foreach { case (e, i) =>
          names.get(e.researcherId) match {
            case None =>
              stats.rejected.incrementAndGet()
              rejects.record(
                Reject(
                  Stage,
                  "ResearcherMetricCacheRanking",
                  s"${r.windowLabel}/${metric.wire}/${e.researcherId}",
                  0,
                  "ranked researcher has no canonical_researcher record (no display name)"
                )
              )
            case Some(name) =>
              val item = RankingItems.item(r.windowLabel, metric, i + 1, e, name)
              stats.items.incrementAndGet()
              stats.writeUnits.addAndGet(ItemSize.writeUnits(ItemHash.stamp(item, 1L)).toLong)
              ws.submit(writer.upsertIfChanged(table, item))
          }
        }
      }
    }
    ws.close()
  }

  private def evidenceBundles(stats: TableLoadStats): Unit = {
    val ws = new Writes(stats)
    manifest.withPrefix("path_evidence:").foreach { t =>
      TableReader.foreach[EvidenceItem](source, t, rejects, Stage) { e =>
        ItemBuilders.evidenceItem(e) match {
          case Left(err) =>
            stats.rejected.incrementAndGet();
            rejects.record(Reject(Stage, "EvidenceBundle", e.evidenceId.value, 0, err))
          case Right(item) =>
            stats.items.incrementAndGet()
            stats.writeUnits.addAndGet(ItemSize.writeUnits(ItemHash.stamp(item, 1L)).toLong)
            ws.submit(writer.upsertIfChanged(tables.evidenceBundleTable, item))
        }
      }
    }
    ws.close()
  }

  /** EntityLinkCache items; also returns the display names of the `wanted` researcher IDs. */
  private def entityLinks(
      stats: TableLoadStats,
      wanted: Set[String]
  ): (Long, mutable.HashMap[String, String]) = {
    var skipped = 0L
    val names = mutable.HashMap.empty[String, String]
    val ws = new Writes(stats)
    TableReader.foreach[CanonicalResearcher](
      source,
      manifest.require("canonical_researcher"),
      rejects,
      Stage
    ) { r =>
      if (wanted.contains(r.researcherId.value)) names.update(r.researcherId.value, r.displayName)
      val (items, s) = ItemBuilders.entityLinks(r)
      skipped += s
      items.foreach { item =>
        stats.items.incrementAndGet()
        stats.writeUnits.addAndGet(ItemSize.writeUnits(ItemHash.stamp(item, 1L)).toLong)
        ws.submit(writer.upsertIfChanged(tables.entityLinkCacheTable, item))
      }
    }
    ws.close()
    (skipped, names)
  }

  private def artifacts(stats: TableLoadStats, files: List[(String, Long, Long, String)]): Unit = {
    val registry = new ArtifactRegistry(kv, tables.artifactRegistryTable)
    files.foreach { case (path, rows, bytes, sha) =>
      stats.items.incrementAndGet()
      val latest = registry.latest("neptune-csv", path)
      if (latest.exists(_.item.strOpt("sha256").contains(sha))) stats.count(Change.Unchanged)
      else
        stats.count(
          registry.register(
            "neptune-csv",
            path,
            latest.map(_.version).getOrElse(0L),
            "sha256" -> AttrValue.S(sha),
            "rows" -> AttrValue.n(rows),
            "bytes" -> AttrValue.n(bytes),
            "sourceRunId" -> AttrValue.S(manifest.runId.value)
          )
        )
    }
  }

  /** One full pass. `neptuneFiles` are (relative path, rows, bytes, sha256) of the CSV files to
    * register.
    */
  def load(
      pass: Int,
      runId: RunId,
      neptuneFiles: List[(String, Long, Long, String)]
  ): DynamoLoadReport = {
    val t0 = System.nanoTime()
    val ledger = RunLedger.open(kv, tables.runLedgerTable, runId, s"dynamodb-load pass $pass")
    val stats = ListMap(
      "ResearcherMetricCache" -> new TableLoadStats("ResearcherMetricCache"),
      "ResearcherMetricCacheRanking" -> new TableLoadStats("ResearcherMetricCacheRanking"),
      "EvidenceBundle" -> new TableLoadStats("EvidenceBundle"),
      "EntityLinkCache" -> new TableLoadStats("EntityLinkCache"),
      "ArtifactRegistry" -> new TableLoadStats("ArtifactRegistry"),
      "QuerySession" -> new TableLoadStats("QuerySession")
    )
    val sizes = new mutable.ArrayBuilder.ofInt
    val rankings = metricCache(stats("ResearcherMetricCache"), sizes)
    evidenceBundles(stats("EvidenceBundle"))
    val (skipped, names) =
      entityLinks(stats("EntityLinkCache"), rankings.flatMap(_.researcherIds).toSet)
    rankingItems(stats("ResearcherMetricCacheRanking"), rankings, names)
    artifacts(stats("ArtifactRegistry"), neptuneFiles)
    val report = DynamoLoadReport(
      pass,
      runId,
      ledger.attempt,
      stats,
      SizeDistribution.of(sizes.result()),
      skipped,
      (System.nanoTime() - t0) / 1000000L
    )
    ledger.record(
      "load-summary",
      (List(
        "dataChanges" -> AttrValue.n(report.dataChanges),
        "sourceRunId" -> AttrValue.S(manifest.runId.value)
      ) ++
        stats.toList.flatMap { case (k, s) =>
          List(
            s"${k}Changes" -> AttrValue.n(s.changes),
            s"${k}Conflicts" -> AttrValue.n(s.conflicts.get),
            s"${k}Rejected" -> AttrValue.n(s.rejected.get)
          )
        }): _*
    )
    report
  }
}
