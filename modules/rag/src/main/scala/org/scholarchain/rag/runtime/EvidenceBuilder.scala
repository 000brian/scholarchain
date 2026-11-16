package org.scholarchain.rag.runtime

import scala.collection.immutable.ListMap

import org.scholarchain.core._

/** The values an evidence item carries, in the canonical form written by [[EvidenceBuilder]] and
  * read back by [[ClaimVerification]]. Non-metric values live in the item's snippet as `key=value`
  * lines (the snippet's content hash is in the provenance); the metric value is the item's `metric`
  * field; window years come from the item's window.
  */
object EvidenceValues {

  /** Numeric fields a claim may bind a number to (schema `answer-claims-v1`, `numbers[].field`). */
  val Fields: List[String] = List(
    "value",
    "rank",
    "weight",
    "jointPublicationCount",
    "firstYear",
    "lastYear",
    "hops",
    "maxDepth",
    "depthSearched",
    "year",
    "teamSize",
    "confidence",
    "publicationCount",
    "distinctCoauthors",
    "windowStartYear",
    "windowEndYear"
  )

  /** Fields whose values are calendar years. */
  val YearFields: Set[String] =
    Set("firstYear", "lastYear", "year", "windowStartYear", "windowEndYear")

  def snippet(pairs: List[(String, String)]): String =
    pairs.map { case (k, v) => s"$k=${v.replace('\n', ' ')}" }.mkString("\n")

  /** The `key=value` lines of a snippet (first `=` splits). */
  def pairs(item: EvidenceItem): ListMap[String, String] =
    ListMap.from(item.snippet.toList.flatMap(_.split('\n').toList).flatMap { l =>
      val i = l.indexOf('=')
      if (i <= 0) None else Some(l.substring(0, i) -> l.substring(i + 1))
    })

  /** Every numeric field of an item. */
  def numbers(item: EvidenceItem): ListMap[String, Double] = {
    val p = pairs(item)
    val fromSnippet = Fields.flatMap(f => p.get(f).flatMap(_.toDoubleOption).map(f -> _))
    val metric = item.metric.toList.map(m => "value" -> m.value)
    val window = item.window.toList.flatMap(w =>
      List("windowStartYear" -> w.startYear.toDouble, "windowEndYear" -> w.endYear.toDouble)
    )
    ListMap.from(metric ++ fromSnippet.filterNot(x => metric.nonEmpty && x._1 == "value") ++ window)
  }

  /** Researcher (and group) IDs an item covers: subject IDs, IDs inside snippet values and source
    * record IDs.
    */
  def ids(item: EvidenceItem): Set[String] = {
    val idRe = "(researcher|work|evidence|edge):v1:[0-9a-f]{32}".r
    (item.subjectIds ++ pairs(item).values ++ item.provenance.sourceRecordIds)
      .flatMap(s => idRe.findAllIn(s).toList)
      .toSet
  }

  /** Researcher display names an item covers (from `name*` and list fields). */
  def names(item: EvidenceItem): Set[String] = {
    val p = pairs(item)
    val single = List("name", "nameA", "nameB").flatMap(p.get)
    val listed =
      List("pathNames", "topCoauthorNames").flatMap(p.get).flatMap(_.split(" \\| ").toList)
    (single ++ listed).map(_.trim).filter(_.nonEmpty).toSet
  }

  def title(item: EvidenceItem): Option[String] =
    if (item.evidenceType == EvidenceType.Work) pairs(item).get("title") else None
}

/** One row of the compact facts table given to the synthesis model. Graph rows and semantic rows
  * are kept apart.
  */
final case class FactRow(
    evidenceId: EvidenceId,
    evidenceType: EvidenceType,
    window: Option[YearWindow],
    values: String,
    priority: Int
) {
  def render: String =
    s"${evidenceId.value} | ${evidenceType.wire} | ${window.map(Fact.windowText).getOrElse("-")} | $values"
}

/** The bundle and the model's facts table built from the retrieved facts. */
final case class BuiltEvidence(
    bundle: Option[EvidenceBundle],
    graphRows: List[FactRow],
    semanticRows: List[(FactRow, Option[String])],
    idOf: Map[Fact, EvidenceId],
    capped: Boolean,
    droppedFacts: Int
) {
  def items: List[EvidenceItem] = bundle.map(_.items).getOrElse(Nil)
  def item(id: EvidenceId): Option[EvidenceItem] = items.find(_.evidenceId == id)
}

/** Stage 7, EvidenceBuilder: turns retrieved facts into a core [[EvidenceBundle]] with runtime
  * provenance (sourceType `runtime`, runId = the query run; `sourceRecordIds` name the stored
  * record and the Spark run). At most `runtime.maxBundleItems` items, kept in fact priority order.
  */
final class EvidenceBuilder(ctx: RuntimeContext) {
  import EvidenceBuilder._

  def build(queryRunId: RunId, facts: List[Fact]): Either[String, BuiltEvidence] = {
    val ordered = facts.zipWithIndex.sortBy { case (f, i) => (f.priority, i) }.map(_._1)
    val withItems = ordered.map(f => f -> item(queryRunId, f))
    withItems.collectFirst { case (_, Left(e)) => e } match {
      case Some(e) => Left(e)
      case None    =>
        val all = withItems.collect { case (f, Right(i)) => (f, i) }
        // one item per evidence ID (a researcher can be both ranked and looked up)
        val firstById = all.foldLeft(ListMap.empty[EvidenceId, EvidenceItem]) {
          case (acc, (_, i)) =>
            if (acc.contains(i.evidenceId)) acc else acc + (i.evidenceId -> i)
        }
        val kept = firstById.values.toList.take(ctx.runtime.maxBundleItems)
        val keptIds = kept.map(_.evidenceId).toSet
        val idOf = all.collect {
          case (f, i) if keptIds.contains(i.evidenceId) => f -> i.evidenceId
        }.toMap
        val errors = kept.flatMap(i => Contract.validate(i).map(e => s"${i.evidenceId}: $e"))
        if (errors.nonEmpty)
          Left(s"evidence item failed its contract: ${errors.take(3).mkString("; ")}")
        else {
          val bundle =
            if (kept.isEmpty) None
            else
              Some(
                EvidenceBundle(
                  1,
                  EvidenceId.forBundle(queryRunId, kept.map(_.evidenceId)),
                  kept,
                  Provenance(
                    1,
                    SourceType.Runtime,
                    DatasetId,
                    ctx.graphRunId.value,
                    List(ctx.graphRunId.value),
                    queryRunId,
                    JobName,
                    BundlePolicy,
                    None
                  )
                )
              )
          bundle.map(Contract.validate(_)).getOrElse(Nil) match {
            case Nil =>
              val factOf = all.groupBy(_._2.evidenceId).view.mapValues(_.head._1).toMap
              val rows = kept.map(i => factOf(i.evidenceId) -> i)
              Right(
                BuiltEvidence(
                  bundle,
                  rows.collect {
                    case (f, i) if i.evidenceType != EvidenceType.SemanticContext => row(f, i)
                  },
                  rows.collect { case (f: SemanticFact, i) => (row(f, i), f.excerpt) },
                  idOf,
                  capped = firstById.size > kept.size,
                  droppedFacts = firstById.size - kept.size
                )
              )
            case errs =>
              Left(s"evidence bundle failed its contract: ${errs.take(3).mkString("; ")}")
          }
        }
    }
  }

  private def row(f: Fact, i: EvidenceItem): FactRow = {
    val values = f match {
      case s: SemanticFact =>
        s"documentType=${s.entry.documentType.wire}; vectorKey=${s.entry.vectorKey}; score=${Fact.fmt(s.entry.score)}; descriptive context only"
      case _ =>
        val p = EvidenceValues.pairs(i)
        val metric =
          i.metric.map(m => s"metric=${m.metric.wire}; value=${Fact.fmt(m.value)}; ").getOrElse("")
        metric + p.filterNot(_._1 == "metric").map { case (k, v) => s"$k=$v" }.mkString("; ")
    }
    FactRow(i.evidenceId, i.evidenceType, i.window, values, f.priority)
  }

  private def prov(
      runId: RunId,
      src: SourceRef,
      snippet: Option[String],
      extra: List[String] = Nil
  ) =
    Provenance(
      1,
      SourceType.Runtime,
      DatasetId,
      src.snapshotId,
      (List(src.recordRef, src.sparkRunId.value) ++ extra).distinct,
      runId,
      JobName,
      src.policyVersion,
      snippet.map(ContentHash.of)
    )

  private def mk(
      runId: RunId,
      t: EvidenceType,
      subjects: List[String],
      window: Option[YearWindow],
      metric: Option[MetricValue],
      pairs: List[(String, String)],
      src: SourceRef,
      extra: List[String] = Nil
  ): Either[String, EvidenceItem] = {
    val snippet = if (pairs.isEmpty) None else Some(EvidenceValues.snippet(pairs))
    Right(
      EvidenceItem(
        1,
        EvidenceId.of(t, runId, subjects),
        t,
        subjects,
        window,
        metric,
        snippet,
        prov(runId, src, snippet, extra)
      )
    )
  }

  private def item(runId: RunId, f: Fact): Either[String, EvidenceItem] = f match {
    case m: MetricFact =>
      mk(
        runId,
        EvidenceType.Metric,
        List(m.researcher.id.value, s"metric=${m.metric.wire}", s"window=${m.windowLabel}"),
        Some(m.window),
        Some(MetricValue(m.metric, m.value)),
        List("name" -> m.researcher.displayName, "researcherId" -> m.researcher.id.value) ++
          m.rank.map(r => "rank" -> r.toString) ++
          m.groupId.map(g => "groupId" -> g.value) ++
          List("windowLabel" -> m.windowLabel),
        m.source
      )
    case e: EdgeFact =>
      val (x, y) = ordered(e.a, e.b)
      mk(
        runId,
        EvidenceType.CoauthorEdge,
        List(x.id.value, y.id.value, s"window=${e.windowLabel}"),
        Some(e.window),
        None,
        List(
          "nameA" -> x.displayName,
          "researcherA" -> x.id.value,
          "nameB" -> y.displayName,
          "researcherB" -> y.id.value,
          "weight" -> Fact.fmt(e.weight),
          "jointPublicationCount" -> e.jointPublicationCount.toString,
          "firstYear" -> e.firstYear.toString,
          "lastYear" -> e.lastYear.toString,
          "supportingWorkIds" -> e.supportingWorkIds.mkString(","),
          "edgeId" -> e.edgeId,
          "windowLabel" -> e.windowLabel
        ),
        e.source,
        List(e.edgeId)
      )
    case e: EdgeAbsentFact =>
      val (x, y) = ordered(e.a, e.b)
      mk(
        runId,
        EvidenceType.CoauthorEdge,
        List(x.id.value, y.id.value, s"window=${e.windowLabel}", "edge=none"),
        Some(e.window),
        None,
        List(
          "found" -> "false",
          "nameA" -> x.displayName,
          "researcherA" -> x.id.value,
          "nameB" -> y.displayName,
          "researcherB" -> y.id.value,
          "windowLabel" -> e.windowLabel
        ),
        e.source
      )
    case p: PathFact =>
      mk(
        runId,
        EvidenceType.GraphPath,
        p.researchers.map(_.id.value) :+ s"window=${p.windowLabel}",
        Some(p.window),
        None,
        List(
          "hops" -> p.hops.toString,
          "origin" -> p.origin,
          "pathNames" -> p.researchers.map(_.displayName).mkString(" | "),
          "pathIds" -> p.researchers.map(_.id.value).mkString(","),
          "moreThanOneShortestPath" -> p.truncated.toString,
          "windowLabel" -> p.windowLabel
        ) ++ p.sparkEvidenceId.map(id => "sparkEvidenceId" -> id.value),
        p.source,
        p.sparkEvidenceId.map(_.value).toList
      )
    case p: PathAbsentFact =>
      mk(
        runId,
        EvidenceType.GraphPath,
        List(
          p.from.id.value,
          p.to.id.value,
          s"window=${p.windowLabel}",
          "path=none",
          s"maxDepth=${p.maxDepth}"
        ),
        Some(p.window),
        None,
        List(
          "found" -> "false",
          "complete" -> p.complete.toString,
          "maxDepth" -> p.maxDepth.toString,
          "depthSearched" -> p.depthSearched.toString,
          "nameA" -> p.from.displayName,
          "researcherA" -> p.from.id.value,
          "nameB" -> p.to.displayName,
          "researcherB" -> p.to.id.value,
          "windowLabel" -> p.windowLabel
        ),
        p.source
      )
    case w: WorkFact =>
      mk(
        runId,
        EvidenceType.Work,
        List(w.workId.value),
        Some(YearWindow(w.year, w.year)),
        None,
        List(
          "title" -> w.title,
          "year" -> w.year.toString,
          "venueKey" -> w.venueKey,
          "teamSize" -> w.teamSize.toString,
          "dblpKey" -> w.dblpKey
        ),
        w.source
      )
    case i: IdentityFact =>
      mk(
        runId,
        EvidenceType.MatchDecision,
        List(i.researcher.id.value, s"identity=${AuthorNameKey.foldedName(i.askedName)}"),
        None,
        None,
        List(
          "name" -> i.researcher.displayName,
          "researcherId" -> i.researcher.id.value,
          "askedName" -> i.askedName,
          "matchedValue" -> i.matchedValue,
          "resolved" -> i.resolved.toString,
          "outcome" -> i.outcome
        ) ++ i.confidence.map(c => "confidence" -> Fact.fmt(c)) ++
          i.publicationCount.map(v => "publicationCount" -> Fact.fmt(v)) ++
          i.distinctCoauthors.map(v => "distinctCoauthors" -> Fact.fmt(v)) ++
          (if (i.topCoauthors.isEmpty) Nil
           else
             List(
               "topCoauthorNames" -> i.topCoauthors.map(_.displayName).mkString(" | "),
               "topCoauthorIds" -> i.topCoauthors.map(_.id.value).mkString(",")
             )) ++
          List("contextWindowLabel" -> i.fullWindowLabel),
        i.source
      )
    case s: SemanticFact =>
      val e = s.entry
      val window = (e.windowStartYear, e.windowEndYear) match {
        case (Some(a), Some(b)) => Some(YearWindow(a, b))
        case _                  => None
      }
      Right(
        EvidenceItem(
          1,
          EvidenceId.of(EvidenceType.SemanticContext, runId, e.vectorKey :: e.subjectIds),
          EvidenceType.SemanticContext,
          e.vectorKey :: e.subjectIds,
          window,
          None,
          None,
          Provenance(
            1,
            SourceType.Runtime,
            DatasetId,
            ctx.graphRunId.value,
            List(e.vectorKey, e.textS3Uri),
            runId,
            JobName,
            SemanticPolicy,
            Some(e.textHash)
          )
        )
      )
  }
}

object EvidenceBuilder {
  val JobName = "rag.query"
  val DatasetId = "scholarchain-graph"
  val BundlePolicy = "runtime-evidence/1"
  val SemanticPolicy = "semantic-context-v1"

  private def ordered(a: ResearcherRef, b: ResearcherRef): (ResearcherRef, ResearcherRef) =
    if (CodePointOrder.lessThan(a.id.value, b.id.value)) (a, b) else (b, a)
}
