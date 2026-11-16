package org.scholarchain.storage.runtime

import java.nio.charset.StandardCharsets

import scala.collection.immutable.ListMap
import scala.util.control.NonFatal

import org.scholarchain.core._
import org.scholarchain.storage.DynamoDbLimits
import org.scholarchain.storage.dynamo._
import org.scholarchain.storage.ports._

// Typed runtime access to the DynamoDB tables. Storage owns every item format and key; callers
// (rag, services) pass typed values and never build a key. Every reader works on any
// KeyValueStore: the in-memory tables and the AWS adapter alike.

/** One ResearcherMetricCache item (RM1): every stored metric of one researcher in one window.
  *
  * @param key
  *   the stored item key; [[recordRef]] names it in provenance `sourceRecordIds`
  */
final case class MetricCacheEntry(
    researcherId: ResearcherId,
    windowLabel: String,
    window: YearWindow,
    values: ListMap[CollaborationMetric, Double],
    componentId: ResearcherId,
    communityId: ResearcherId,
    mrWorkIds: List[WorkId],
    identityConfidence: Double,
    runId: RunId,
    snapshotId: String,
    policyVersion: String,
    key: ItemKey
) {
  def value(m: CollaborationMetric): Option[Double] = values.get(m)
  def recordRef: String = s"ResearcherMetricCache:${key.pk}/${key.sk}"
}

/** One RM4 ranking item. `rank` starts at 1 (an ordinal: ties are already broken by the stored
  * order).
  */
final case class RankingEntry(
    rank: Int,
    researcherId: ResearcherId,
    displayName: String,
    value: Double,
    windowLabel: String,
    metric: CollaborationMetric,
    runId: RunId,
    policyVersion: String,
    key: ItemKey
) {
  def recordRef: String = s"ResearcherMetricCache:${key.pk}/${key.sk}"
}

/** Reads ResearcherMetricCache: RM1 (one researcher, one window), RM2 (every window of one
  * researcher) and RM4 (top-K of a ranking). Window labels must be configured `graph.windows`
  * labels; ranking metrics must be in [[RankingMetrics.ranked]].
  */
final class MetricCacheReader(
    kv: KeyValueStore,
    table: String,
    windowLabels: List[String],
    maxTopK: Int
) {
  import MetricCacheReader._

  private def window(w: String): Either[String, String] =
    if (w != null && windowLabels.contains(w)) Right(w)
    else Left(s"window '$w' is not a configured window (${windowLabels.mkString(", ")})")
  private def researcher(r: ResearcherId): Either[String, ResearcherId] =
    if (r != null && r.isWellFormed) Right(r) else Left(s"'$r' is not a researcher ID")

  /** RM1. Right(None) when the researcher has no item in that window. */
  def get(
      researcherId: ResearcherId,
      windowLabel: String
  ): Either[String, Option[MetricCacheEntry]] = RuntimeStores.guard {
    for {
      r <- researcher(researcherId)
      w <- window(windowLabel)
      item = kv.get(table, ItemKey(TableSpecs.researcherPk(r.value), TableSpecs.windowLabelSk(w)))
      e <- item.fold[Either[String, Option[MetricCacheEntry]]](Right(None))(i =>
        decodeEntry(i).map(Some(_))
      )
    } yield e
  }

  /** RM2: the researcher's items in the configured window order (windows without an item are
    * absent).
    */
  def allWindows(researcherId: ResearcherId): Either[String, List[MetricCacheEntry]] =
    RuntimeStores.guard(researcher(researcherId).flatMap { r =>
      val items = kv.query(
        table,
        TableSpecs.researcherPk(r.value),
        Some(TableSpecs.WindowSkPrefix),
        math.max(1, windowLabels.size),
        descending = false
      )
      sequence(items.map(decodeEntry)).map(
        _.filter(e => windowLabels.contains(e.windowLabel)).sortBy(e =>
          windowLabels.indexOf(e.windowLabel)
        )
      )
    })

  /** RM4: the first `topK` (1..runtime.rankingCacheTopK) entries of a ranking, best first. */
  def ranking(
      windowLabel: String,
      metric: CollaborationMetric,
      topK: Int
  ): Either[String, List[RankingEntry]] = RuntimeStores.guard {
    for {
      w <- window(windowLabel)
      m <- Either.cond(
        metric != null && RankingMetrics.isRanked(metric),
        metric,
        s"metric '${Option(metric).map(_.wire).orNull}' is not ranked " +
          s"(${RankingMetrics.ranked.map(_.wire).mkString(", ")})"
      )
      k <- Either.cond(topK >= 1 && topK <= maxTopK, topK, s"topK $topK is outside 1..$maxTopK")
      items = kv.query(
        table,
        TableSpecs.rankingPk(w, m.wire),
        Some(TableSpecs.RankSkPrefix),
        k,
        descending = false
      )
      entries <- sequence(items.map(decodeRanking))
    } yield entries
  }
}

object MetricCacheReader {

  /** Item attribute name of each stored metric (see [[MetricCacheAccumulator]]). */
  val Attributes: ListMap[String, CollaborationMetric] = ListMap(
    "P" -> CollaborationMetric.PublicationCount,
    "C" -> CollaborationMetric.DistinctCoauthors,
    "T" -> CollaborationMetric.TotalCoauthorOccurrences,
    "A" -> CollaborationMetric.AverageCoauthorsPerPaper,
    "M" -> CollaborationMetric.MaxCoauthorsOnOnePaper,
    "W" -> CollaborationMetric.WeightedDegree,
    "componentSize" -> CollaborationMetric.ComponentSize,
    "communitySize" -> CollaborationMetric.CommunitySize,
    "coreness" -> CollaborationMetric.Coreness,
    "participationCoefficient" -> CollaborationMetric.ParticipationCoefficient,
    "crossCommunityDegree" -> CollaborationMetric.CrossCommunityDegree,
    "communitiesConnected" -> CollaborationMetric.CommunitiesConnected
  )

  private[runtime] def sequence[A](xs: List[Either[String, A]]): Either[String, List[A]] =
    xs.collectFirst { case Left(e) => e }.toLeft(xs.collect { case Right(a) => a })

  private def num(i: Item, k: String): Either[String, Double] = i.get(k) match {
    case Some(AttrValue.N(v)) => v.toDoubleOption.toRight(s"$k '$v' is not a number")
    case other                => Left(s"$k is not a number: $other")
  }
  private def str(i: Item, k: String): Either[String, String] =
    i.strOpt(k).toRight(s"$k is missing")
  private def id[A](s: String, parse: String => Either[String, A]): Either[String, A] = parse(s)

  def decodeEntry(i: Item): Either[String, MetricCacheEntry] =
    (for {
      r <- str(i, "researcherId").flatMap(id(_, ResearcherId.parse))
      w <- str(i, "windowLabel")
      start <- num(i, "windowStartYear")
      end <- num(i, "windowEndYear")
      comp <- str(i, "componentId").flatMap(id(_, ResearcherId.parse))
      comm <- str(i, "communityId").flatMap(id(_, ResearcherId.parse))
      mr <- i.get("mrWorkIds") match {
        case Some(AttrValue.L(xs)) =>
          sequence(xs.map {
            case AttrValue.S(s) => WorkId.parse(s)
            case other          => Left(s"mrWorkIds holds $other")
          })
        case other => Left(s"mrWorkIds is not a list: $other")
      }
      conf <- num(i, "identityConfidence")
      run <- str(i, "runId").flatMap(id(_, RunId.parse))
      snap <- str(i, "snapshotId")
      pol <- str(i, "policyVersion")
      values <- sequence(Attributes.toList.collect {
        case (k, m) if i.get(k).nonEmpty => num(i, k).map(m -> _)
      })
    } yield MetricCacheEntry(
      r,
      w,
      YearWindow(start.toInt, end.toInt),
      ListMap.from(values),
      comp,
      comm,
      mr,
      conf,
      run,
      snap,
      pol,
      i.key
    )).left.map(e => s"malformed ResearcherMetricCache item ${keyOf(i)}: $e")

  def decodeRanking(i: Item): Either[String, RankingEntry] =
    (for {
      rank <- num(i, "rank")
      r <- str(i, "researcherId").flatMap(id(_, ResearcherId.parse))
      name <- str(i, "displayName")
      v <- num(i, "value")
      w <- str(i, "windowLabel")
      m <- str(i, "metric").flatMap(CollaborationMetric.fromWire)
      run <- str(i, "runId").flatMap(id(_, RunId.parse))
      pol <- str(i, "policyVersion")
    } yield RankingEntry(rank.toInt, r, name, v, w, m, run, pol, i.key)).left
      .map(e => s"malformed ranking item ${keyOf(i)}: $e")

  private[runtime] def keyOf(i: Item): String =
    s"${i.strOpt(Item.Pk).getOrElse("?")}/${i.strOpt(Item.Sk).getOrElse("?")}"
}

/** One EntityLinkCache candidate. `matchedValue` is the dblp name (name links) or the ORCID iD. */
final case class EntityLinkCandidate(
    researcherId: ResearcherId,
    displayName: String,
    matchedValue: String,
    linkKind: String,
    keyRule: String,
    runId: RunId,
    policyVersion: String,
    key: ItemKey
) {
  def recordRef: String = s"EntityLinkCache:${key.pk}/${key.sk}"
}

/** Candidates of one lookup, in researcherId order. `truncated` when more than `limit` exist. */
final case class EntityLinkResult(
    lookupKey: String,
    candidates: List[EntityLinkCandidate],
    truncated: Boolean
)

/** Reads EntityLinkCache: EL1 (folded dblp name, author-name-key/1) and EL2 (ORCID iD). */
final class EntityLinkReader(kv: KeyValueStore, table: String) {
  import EntityLinkReader._

  /** Candidates for a typed name. The name is folded here with [[AuthorNameKey.foldedName]] (the
    * dblp homonym suffix, case, accents and punctuation are removed), the rule the loader used.
    */
  def byName(name: String, limit: Int): Either[String, EntityLinkResult] =
    for {
      n <- Either.cond(
        name != null && name.length <= MaxNameLength,
        name,
        s"name must be at most $MaxNameLength characters"
      )
      folded = AuthorNameKey.foldedName(n)
      _ <- Either.cond(folded.nonEmpty, (), "the name has no letters or digits")
      r <- lookup(TableSpecs.namePk(folded), limit)
    } yield r

  /** Candidates for an ORCID iD (bare or `https://orcid.org/` form; the check digit must hold). */
  def byOrcid(orcid: String, limit: Int): Either[String, EntityLinkResult] =
    Orcid
      .normalize(orcid)
      .toRight(s"'$orcid' is not a valid ORCID iD")
      .flatMap(o => lookup(TableSpecs.orcidPk(o), limit))

  private def lookup(pk: String, limit: Int): Either[String, EntityLinkResult] =
    RuntimeStores.guard(for {
      l <- Either.cond(
        limit >= 1 && limit <= MaxLimit,
        limit,
        s"limit $limit is outside 1..$MaxLimit"
      )
      items = kv.query(table, pk, Some("researcher#"), l + 1, descending = false)
      cs <- MetricCacheReader.sequence(items.map(decode))
    } yield EntityLinkResult(pk, cs.take(l), cs.size > l))
}

object EntityLinkReader {
  val MaxLimit: Int = 100
  val MaxNameLength: Int = 200

  def decode(i: Item): Either[String, EntityLinkCandidate] =
    (for {
      r <- i.strOpt("researcherId").toRight("researcherId is missing").flatMap(ResearcherId.parse)
      name <- i.strOpt("displayName").toRight("displayName is missing")
      mv <- i.strOpt("matchedValue").toRight("matchedValue is missing")
      kind <- i.strOpt("linkKind").toRight("linkKind is missing")
      rule <- i.strOpt("keyRule").toRight("keyRule is missing")
      run <- i.strOpt("runId").toRight("runId is missing").flatMap(RunId.parse)
      pol <- i.strOpt("policyVersion").toRight("policyVersion is missing")
    } yield EntityLinkCandidate(r, name, mv, kind, rule, run, pol, i.key)).left
      .map(e => s"malformed EntityLinkCache item ${MetricCacheReader.keyOf(i)}: $e")
}

/** What one answer-bundle write did. `itemConflicts` lists `evidenceId/window` keys that already
  * held different content (never overwritten).
  */
final case class BundleWrite(
    bundleId: EvidenceId,
    bundle: Change,
    itemsInserted: Int,
    itemsUnchanged: Int,
    itemConflicts: List[String],
    encodedBytes: Int
)

/** EvidenceBundle access: EB1 (one evidence item in one window), EB2 (every window of one evidence
  * ID) and EB3 (an answer bundle). Bundles and their items are written put-if-absent (an identical
  * rerun is `Unchanged`; different content is a `Conflict` and is never overwritten), so Phase 10
  * can serve `GET /v1/evidence/{evidenceId}` from EB1/EB2 without changes.
  */
final class EvidenceStore(kv: KeyValueStore, table: String, maxBundleItems: Int) {
  import EvidenceStore._
  private val writer = new ConditionalWriter(kv)

  private def evidenceId(e: EvidenceId) =
    Either.cond(e != null && e.isWellFormed, e, s"'$e' is not an evidence ID")

  private def decodeRecord[A](i: Item)(implicit
      rw: upickle.default.ReadWriter[A],
      v: Validator[A]
  ): Either[String, A] =
    i.strOpt("record")
      .toRight("record is missing")
      .flatMap(Contract.decode[A](_).left.map(_.message))
      .left
      .map(e => s"malformed EvidenceBundle item ${MetricCacheReader.keyOf(i)}: $e")

  /** EB1: the item stored under (evidenceId, window); `window = None` reads the window-less item.
    */
  def get(id: EvidenceId, window: Option[YearWindow]): Either[String, Option[EvidenceItem]] =
    RuntimeStores.guard(evidenceId(id).flatMap { e =>
      val sk = window
        .map(w => TableSpecs.windowSk(w.startYear, w.endYear))
        .getOrElse(TableSpecs.NoWindowSk)
      kv.get(table, ItemKey(TableSpecs.evidencePk(e.value), sk)) match {
        case None    => Right(None)
        case Some(i) => decodeRecord[EvidenceItem](i).map(Some(_))
      }
    })

  /** EB2: every stored window of one evidence ID (at most `limit`, 1..100), in sort-key order. */
  def windows(id: EvidenceId, limit: Int): Either[String, List[EvidenceItem]] =
    RuntimeStores.guard(for {
      e <- evidenceId(id)
      l <- Either.cond(limit >= 1 && limit <= 100, limit, s"limit $limit is outside 1..100")
      items = kv.query(
        table,
        TableSpecs.evidencePk(e.value),
        Some("window#"),
        l,
        descending = false
      )
      out <- MetricCacheReader.sequence(items.map(decodeRecord[EvidenceItem]))
    } yield out)

  /** EB3: an answer bundle by its bundle ID. */
  def getBundle(bundleId: EvidenceId): Either[String, Option[EvidenceBundle]] =
    RuntimeStores.guard(evidenceId(bundleId).flatMap { b =>
      kv.get(table, ItemKey(TableSpecs.evidencePk(b.value), TableSpecs.BundleSk)) match {
        case None    => Right(None)
        case Some(i) => decodeRecord[EvidenceBundle](i).map(Some(_))
      }
    })

  /** Writes every item of `bundle` as an EB1 item, then the bundle as an EB3 item, all put-if-
    * absent. Refused (Left, nothing written) when the bundle fails its contract, has more than
    * `runtime.maxBundleItems` items, or encodes to more than [[MaxBundleBytes]] bytes (DynamoDB
    * items are limited to 400 KB).
    */
  def putBundle(bundle: EvidenceBundle): Either[String, BundleWrite] =
    RuntimeStores.guard(for {
      _ <- Either.cond(
        bundle != null && bundle.items.size <= maxBundleItems,
        (),
        s"bundle has ${Option(bundle).map(_.items.size).getOrElse(0)} items; at most $maxBundleItems"
      )
      json <- Contract.encode(bundle).left.map(_.message)
      bytes = json.getBytes(StandardCharsets.UTF_8).length
      _ <- Either.cond(
        bytes <= MaxBundleBytes,
        (),
        s"bundle encodes to $bytes bytes; at most $MaxBundleBytes"
      )
      bundleItem = Item(
        ListMap[String, AttrValue](
          Item.Pk -> AttrValue.S(TableSpecs.evidencePk(bundle.bundleId.value)),
          Item.Sk -> AttrValue.S(TableSpecs.BundleSk),
          "bundleId" -> AttrValue.S(bundle.bundleId.value),
          "itemCount" -> AttrValue.n(bundle.items.size.toLong),
          "evidenceIds" -> AttrValue.L(bundle.items.map(i => AttrValue.S(i.evidenceId.value))),
          "runId" -> AttrValue.S(bundle.provenance.runId.value),
          "record" -> AttrValue.S(json)
        )
      )
      _ <- Either.cond(
        ItemSize.of(ItemHash.stamp(bundleItem, 1L)) <= DynamoDbLimits.MaxItemBytes,
        (),
        "bundle item exceeds the DynamoDB item size limit"
      )
      itemRows <- MetricCacheReader.sequence(bundle.items.map(ItemBuilders.evidenceItem))
    } yield {
      val changes = itemRows.map(i => i -> writer.append(table, i))
      val bundleChange = writer.append(table, bundleItem)
      BundleWrite(
        bundle.bundleId,
        bundleChange,
        changes.count(_._2 == Change.Inserted),
        changes.count(_._2 == Change.Unchanged),
        changes.collect { case (i, Change.Conflict) =>
          s"${i.str("evidenceId")}/${i.str(Item.Sk)}"
        },
        bytes
      )
    })
}

object EvidenceStore {

  /** Largest Contract-encoded bundle accepted (DynamoDB items are limited to 400 KB). */
  val MaxBundleBytes: Int = 350000
}

/** Where one audit record went. */
final case class AuditWrite(
    queryRunId: RunId,
    attempt: Int,
    key: ItemKey,
    change: Change,
    encodedBytes: Int
)

/** Appends `query-audit` events to RunLedger: one claimed attempt of the query run per append
  * (append-only, conditional put), holding the Contract-encoded [[QueryAudit]] in `record`.
  */
final class QueryAuditWriter(kv: KeyValueStore, table: String) {
  import QueryAuditWriter._

  def append(audit: QueryAudit): Either[String, AuditWrite] =
    for {
      json <- Option(audit).toRight("no audit").flatMap(Contract.encode(_).left.map(_.message))
      bytes = json.getBytes(StandardCharsets.UTF_8).length
      _ <- Either.cond(
        bytes <= MaxRecordBytes,
        (),
        s"audit encodes to $bytes bytes; at most $MaxRecordBytes"
      )
      out <-
        try {
          val ledger = RunLedger.open(kv, table, audit.queryRunId, EventType)
          val change = ledger.record(
            EventType,
            "schemaVersion" -> AttrValue.n(audit.schemaVersion.toLong),
            "questionHash" -> AttrValue.S(audit.questionHash),
            "answerHash" -> AttrValue.S(audit.answerHash),
            "abstained" -> AttrValue.Bool(audit.abstained),
            "record" -> AttrValue.S(json)
          )
          Right(AuditWrite(audit.queryRunId, ledger.attempt, ledger.lastKey.get, change, bytes))
        } catch { case NonFatal(e) => Left(s"audit write failed: ${e.getMessage}") }
    } yield out

  /** Every `query-audit` record of one query run (all attempts, in order; at most `limit` ledger
    * events are read, 1..1000).
    */
  def read(queryRunId: RunId, limit: Int): Either[String, List[QueryAudit]] =
    RuntimeStores.guard(for {
      _ <- Either.cond(limit >= 1 && limit <= 1000, (), s"limit $limit is outside 1..1000")
      items = kv.query(
        table,
        TableSpecs.runPk(queryRunId.value),
        Some("attempt#"),
        limit,
        descending = false
      )
      out <- MetricCacheReader.sequence(
        items
          .filter(_.strOpt("eventType").contains(EventType))
          .map(i =>
            i.strOpt("record")
              .toRight("record is missing")
              .flatMap(Contract.decode[QueryAudit](_).left.map(_.message))
          )
      )
    } yield out)
}

object QueryAuditWriter {
  val EventType = "query-audit"
  val MaxRecordBytes: Int = 350000
}
