package org.scholarchain.storage.dynamo

import scala.collection.immutable.ListMap
import scala.collection.mutable

import org.scholarchain.core.{
  AuthorNameKey,
  CanonicalResearcher,
  CollaborationMetric,
  Contract,
  EvidenceItem,
  ExternalIdType,
  GroupKind,
  Provenance,
  ResearcherGroupMembership,
  ResearcherMetrics,
  YearWindow
}
import org.scholarchain.storage.ports.{AttrValue, Item}

/** Merges the per-window metric tables of one researcher into one ResearcherMetricCache item.
  *
  * Attribute names: `P`, `C`, `T`, `A`, `M`, `W` are the breadth metrics of the course
  * specification (publication count, distinct coauthors, total coauthor occurrences, average
  * coauthors per paper, maximum coauthors on one paper, weighted degree); the others are spelled
  * out. Short names keep items small (most items stay under 1 KB, one write unit).
  */
final class MetricCacheAccumulator(val researcherId: String, val windowLabel: String) {
  var window: Option[YearWindow] = None
  val values: mutable.LinkedHashMap[CollaborationMetric, Double] = mutable.LinkedHashMap.empty
  var identityConfidence: Option[Double] = None
  var componentId: Option[String] = None
  var communityId: Option[String] = None
  var mrWorkIds: Option[List[String]] = None
  var provenance: Option[Provenance] = None

  /** Provenance of each metric family as read (breadth, group_size, bridges, kcore). The RM1 item
    * keeps the breadth provenance (unchanged); RM4 ranking items use the family of their metric.
    */
  val familyProvenance: mutable.Map[String, Provenance] = mutable.Map.empty
  val families: mutable.Set[String] = mutable.Set.empty

  private def setWindow(w: YearWindow): Unit =
    if (window.exists(_ != w))
      throw new IllegalArgumentException(s"$researcherId/$windowLabel: windows disagree")
    else window = Some(w)

  def addMetrics(family: String, m: ResearcherMetrics): Unit = {
    setWindow(m.window)
    families += family
    m.values.foreach(v => values.update(v.metric, v.value))
    identityConfidence = Some(m.identityConfidence)
    familyProvenance.update(family, m.provenance)
    if (family == "breadth") provenance = Some(m.provenance)
  }

  def addMembership(g: ResearcherGroupMembership): Unit = {
    setWindow(g.window)
    g.groupKind match {
      case GroupKind.Component => families += "components"; componentId = Some(g.groupId.value)
      case GroupKind.Community => families += "communities"; communityId = Some(g.groupId.value)
    }
  }

  /** The works behind M_r: a `metric` evidence item whose subjectIds are the researcher followed by
    * the work IDs.
    */
  def addMetricEvidence(e: EvidenceItem): Unit = {
    e.window.foreach(setWindow)
    families += "evidence"
    mrWorkIds = Some(e.subjectIds.drop(1))
  }

  /** The families a complete item needs. */
  val RequiredFamilies: Set[String] =
    Set("breadth", "group_size", "bridges", "kcore", "components", "communities", "evidence")

  def missing: Set[String] = RequiredFamilies -- families

  /** The item, or Left when any family is missing (rejected, never defaulted). */
  def toItem: Either[String, Item] = {
    import AttrValue._
    def metric(m: CollaborationMetric): Option[AttrValue] = values.get(m).map(AttrValue.n)
    def req(name: String, m: CollaborationMetric): Either[String, (String, AttrValue)] =
      metric(m).map(name -> _).toRight(s"missing metric ${m.wire}")
    if (missing.nonEmpty)
      Left(s"$researcherId/$windowLabel: missing ${missing.toList.sorted.mkString(",")}")
    else {
      val w = window.get
      val p = provenance.get
      val required = List(
        req("P", CollaborationMetric.PublicationCount),
        req("C", CollaborationMetric.DistinctCoauthors),
        req("T", CollaborationMetric.TotalCoauthorOccurrences),
        req("M", CollaborationMetric.MaxCoauthorsOnOnePaper),
        req("W", CollaborationMetric.WeightedDegree),
        req("componentSize", CollaborationMetric.ComponentSize),
        req("communitySize", CollaborationMetric.CommunitySize),
        req("coreness", CollaborationMetric.Coreness),
        req("crossCommunityDegree", CollaborationMetric.CrossCommunityDegree),
        req("communitiesConnected", CollaborationMetric.CommunitiesConnected)
      )
      required.collectFirst { case Left(e) => e } match {
        case Some(e) => Left(s"$researcherId/$windowLabel: $e")
        case None    =>
          val metrics = required.collect { case Right(kv) => kv }
          val optional = List(
            metric(CollaborationMetric.AverageCoauthorsPerPaper).map("A" -> _),
            metric(CollaborationMetric.ParticipationCoefficient).map(
              "participationCoefficient" -> _
            )
          ).flatten
          Right(
            Item(
              ListMap[String, AttrValue](
                Item.Pk -> S(TableSpecs.researcherPk(researcherId)),
                Item.Sk -> S(TableSpecs.windowLabelSk(windowLabel)),
                "researcherId" -> S(researcherId),
                "windowLabel" -> S(windowLabel),
                "windowStartYear" -> AttrValue.n(w.startYear.toLong),
                "windowEndYear" -> AttrValue.n(w.endYear.toLong)
              ) ++ metrics ++ optional ++ ListMap[String, AttrValue](
                "componentId" -> S(componentId.get),
                "communityId" -> S(communityId.get),
                "mrWorkIds" -> L(mrWorkIds.get.map(S(_))),
                "identityConfidence" -> AttrValue.n(identityConfidence.get),
                "runId" -> S(p.runId.value),
                "snapshotId" -> S(p.snapshotId),
                "policyVersion" -> S(p.policyVersion)
              )
            )
          )
      }
    }
  }
}

object ItemBuilders {
  import AttrValue._

  /** One EvidenceBundle item per (evidenceId, window); `record` is the full Contract-encoded
    * EvidenceItem, so nothing is lost.
    */
  def evidenceItem(e: EvidenceItem): Either[String, Item] =
    Contract.encode(e).left.map(_.message).map { json =>
      val sk = e.window
        .map(w => TableSpecs.windowSk(w.startYear, w.endYear))
        .getOrElse(TableSpecs.NoWindowSk)
      Item(
        ListMap[String, AttrValue](
          Item.Pk -> S(TableSpecs.evidencePk(e.evidenceId.value)),
          Item.Sk -> S(sk),
          "evidenceId" -> S(e.evidenceId.value),
          "evidenceType" -> S(e.evidenceType.wire),
          "subjectIds" -> L(e.subjectIds.map(S(_)))
        ) ++ e.window.toList.flatMap(w =>
          List(
            "windowStartYear" -> AttrValue.n(w.startYear.toLong),
            "windowEndYear" -> AttrValue.n(w.endYear.toLong)
          )
        ) ++ e.provenance.contentHash.map("contentHash" -> S(_)) ++ ListMap[String, AttrValue](
          "runId" -> S(e.provenance.runId.value),
          "record" -> S(json)
        )
      )
    }

  /** EntityLinkCache items of one canonical researcher: one per distinct folded dblp name
    * (author-name-key/1) and one per ORCID iD. Names that fold to nothing are skipped and counted
    * by the caller (returned as the second value).
    */
  def entityLinks(r: CanonicalResearcher): (List[Item], Int) = {
    val folded = r.dblpNames.map(n => n -> AuthorNameKey.foldedName(n))
    val skipped = folded.count(_._2.isEmpty)
    val byKey = folded.filter(_._2.nonEmpty).groupBy(_._2).toList.sortBy(_._1).map { case (k, ns) =>
      (TableSpecs.namePk(k), "name", ns.map(_._1).sorted.head)
    }
    val orcids = r.externalIds
      .filter(_.idType == ExternalIdType.Orcid)
      .map(_.value)
      .distinct
      .sorted
      .map(o => (TableSpecs.orcidPk(o), "orcid", o))
    val items = (byKey ++ orcids).map { case (pk, kind, source) =>
      Item(
        Item.Pk -> S(pk),
        Item.Sk -> S(TableSpecs.researcherSk(r.researcherId.value)),
        "linkKind" -> S(kind),
        "matchedValue" -> S(source),
        "researcherId" -> S(r.researcherId.value),
        "displayName" -> S(r.displayName),
        "keyRule" -> S(AuthorNameKey.Version),
        "runId" -> S(r.provenance.runId.value),
        "policyVersion" -> S(r.provenance.policyVersion)
      )
    }
    (items, skipped)
  }

  /** A QuerySession turn (runtime, Phase 9+). `expiresAt` (epoch seconds) is the TTL attribute. */
  def sessionTurn(
      sessionId: String,
      turn: Int,
      questionHash: String,
      bundleId: Option[String],
      expiresAtEpochSeconds: Long
  ): Item =
    Item(
      ListMap[String, AttrValue](
        Item.Pk -> S(TableSpecs.sessionPk(sessionId)),
        Item.Sk -> S(TableSpecs.turnSk(turn)),
        "questionHash" -> S(questionHash),
        "expiresAt" -> AttrValue.n(expiresAtEpochSeconds)
      ) ++ bundleId.map("bundleId" -> S(_))
    )
}
