package org.scholarchain.core

import upickle.default.{read, write}

import Fixtures._

/** Phase 6 graph-analytics contract additions: the new [[CollaborationMetric]] values,
  * [[GroupKind]], [[ResearcherGroupMembership]] v1, and the documented [[EvidenceItem]] shapes for
  * materialized paths and the works behind M_r. Runs under Scala 3 and Scala 2.13.
  */
class GraphAnalyticsContractSuite extends munit.FunSuite {

  def hasError(result: Either[ContractError, _], fragment: String): Boolean =
    result.left.exists(_.messages.exists(_.contains(fragment)))

  def assertRejected[A](a: A, fragment: String)(implicit
      v: Validator[A],
      loc: munit.Location
  ): Unit = {
    val errors = Contract.validate(a)
    assert(errors.exists(_.contains(fragment)), s"expected '$fragment', got $errors")
  }

  def roundTrip[A](a: A)(implicit
      rw: upickle.default.ReadWriter[A],
      v: Validator[A]
  ): Either[ContractError, A] =
    Contract.encode(a).flatMap(json => Contract.decode[A](json))

  val membershipJson: String = Contract.encode(researcherGroupMembership).toOption.get

  def mutate(json: String)(f: ujson.Value => Unit): String = {
    val tree = ujson.read(json)
    f(tree)
    tree.render()
  }

  // ---- CollaborationMetric ---------------------------------------------------------------

  test("existing collaboration metric wire names are unchanged") {
    import CollaborationMetric._
    val expected: List[(CollaborationMetric, String)] = List(
      DistinctCoauthors -> "distinct_coauthors",
      CoauthoredWorks -> "coauthored_works",
      WeightedDegree -> "weighted_degree",
      BetweennessCentrality -> "betweenness_centrality",
      BridgeScore -> "bridge_score",
      ComponentSize -> "component_size",
      ClusteringCoefficient -> "clustering_coefficient"
    )
    expected.foreach { case (m, w) =>
      assertEquals(write[CollaborationMetric](m), "\"" + w + "\"")
      assertEquals(read[CollaborationMetric]("\"" + w + "\""), m)
    }
    assertEquals(CollaborationMetric.values.take(expected.size), expected.map(_._1))
  }

  val phase6Metrics: List[(CollaborationMetric, String)] = {
    import CollaborationMetric._
    List(
      PublicationCount -> "publication_count",
      TotalCoauthorOccurrences -> "total_coauthor_occurrences",
      AverageCoauthorsPerPaper -> "average_coauthors_per_paper",
      MaxCoauthorsOnOnePaper -> "max_coauthors_on_one_paper",
      CommunitySize -> "community_size",
      ParticipationCoefficient -> "participation_coefficient",
      CrossCommunityDegree -> "cross_community_degree",
      CommunitiesConnected -> "communities_connected",
      Coreness -> "coreness"
    )
  }

  test("Phase 6 collaboration metrics encode to their wire names and decode back") {
    phase6Metrics.foreach { case (m, w) =>
      assertEquals(write[CollaborationMetric](m), "\"" + w + "\"")
      assertEquals(read[CollaborationMetric]("\"" + w + "\""), m)
      assertEquals(CollaborationMetric.fromWire(w), Right(m))
    }
  }

  test("new metrics are distinct values, not aliases of coauthored_works or bridge_score") {
    import CollaborationMetric._
    assertNotEquals[CollaborationMetric, CollaborationMetric](PublicationCount, CoauthoredWorks)
    assertNotEquals[CollaborationMetric, CollaborationMetric](ParticipationCoefficient, BridgeScore)
    assertEquals(
      CollaborationMetric.values.map(_.wire).distinct.size,
      CollaborationMetric.values.size
    )
  }

  test("ResearcherMetrics carrying every Phase 6 metric round-trips through the contract") {
    val values = MetricValue(CollaborationMetric.DistinctCoauthors, 2.0) ::
      MetricValue(CollaborationMetric.WeightedDegree, 1.0) ::
      MetricValue(CollaborationMetric.ComponentSize, 2.0) ::
      phase6Metrics.zipWithIndex.map { case ((m, _), i) => MetricValue(m, i.toDouble + 0.5) }
    val metrics = researcherMetrics.copy(values = values)
    assertEquals(roundTrip(metrics), Right(metrics))
    val json = Contract.encode(metrics).toOption.get
    assertEquals(
      ujson.read(json)("values").arr.map(_("metric").str).toList,
      values.map(_.metric.wire)
    )
  }

  test("an unknown metric wire name is rejected on decode") {
    val json = Contract.encode(researcherMetrics).toOption.get
    val bad = mutate(json)(_("values")(0)("metric") = ujson.Str("publications"))
    assert(hasError(Contract.decode[ResearcherMetrics](bad), "unknown collaboration metric"))
  }

  // ---- GroupKind -------------------------------------------------------------------------

  test("GroupKind wire names round-trip and unknown names are rejected") {
    assertEquals(write[GroupKind](GroupKind.Component), "\"component\"")
    assertEquals(write[GroupKind](GroupKind.Community), "\"community\"")
    GroupKind.values.foreach(k => assertEquals(read[GroupKind](write[GroupKind](k)), k))
    assert(GroupKind.fromWire("cluster").isLeft)
    assert(GroupKind.fromWire("Component").isLeft)
  }

  // ---- ResearcherGroupMembership ---------------------------------------------------------

  test("ResearcherGroupMembership: component fixture round-trips with frozen field order") {
    assertEquals(roundTrip(researcherGroupMembership), Right(researcherGroupMembership))
    assertEquals(
      ujson.read(membershipJson).obj.keys.toList,
      List(
        "schemaVersion",
        "researcherId",
        "window",
        "groupKind",
        "groupId",
        "groupSize",
        "provenance"
      )
    )
    assertEquals(ujson.read(membershipJson)("groupKind"), ujson.Str("component"))
  }

  test("ResearcherGroupMembership: a community whose label exceeds the member ID round-trips") {
    val community = researcherGroupMembership.copy(
      researcherId = ResearcherId(edgeLeft),
      groupKind = GroupKind.Community,
      groupId = ResearcherId(edgeRight),
      groupSize = 2
    )
    assertEquals(Contract.validate(community), Nil)
    assertEquals(roundTrip(community), Right(community))
    assertEquals(
      ujson.read(Contract.encode(community).toOption.get)("groupKind"),
      ujson.Str("community")
    )
  }

  test("ResearcherGroupMembership: a singleton component names the researcher itself") {
    val singleton = researcherGroupMembership.copy(
      researcherId = ResearcherId(edgeLeft),
      groupId = ResearcherId(edgeLeft),
      groupSize = 1
    )
    assertEquals(roundTrip(singleton), Right(singleton))
  }

  test("ResearcherGroupMembership: missing or null provenance is rejected") {
    val missing = mutate(membershipJson)(_.obj.remove("provenance"))
    assert(Contract.decode[ResearcherGroupMembership](missing).isLeft)
    assertRejected(
      researcherGroupMembership.copy(provenance = null),
      "provenance: missing (null)"
    )
    val nulled = mutate(membershipJson)(_("provenance") = ujson.Null)
    assert(
      hasError(Contract.decode[ResearcherGroupMembership](nulled), "provenance: missing (null)")
    )
  }

  test("ResearcherGroupMembership: a bad groupKind is rejected on decode") {
    val bad = mutate(membershipJson)(_("groupKind") = ujson.Str("cluster"))
    val result = Contract.decode[ResearcherGroupMembership](bad)
    assert(hasError(result, "unknown group kind 'cluster'"), result)
  }

  test("ResearcherGroupMembership: unsupported schema version is rejected") {
    assertRejected(
      researcherGroupMembership.copy(schemaVersion = 2),
      "schemaVersion: unsupported version 2 for ResearcherGroupMembership"
    )
  }

  test("ResearcherGroupMembership: malformed or wrong-kind group IDs are rejected") {
    val malformed = mutate(membershipJson)(_("groupId") = ujson.Str("researcher:v1:XYZ"))
    assert(hasError(Contract.decode[ResearcherGroupMembership](malformed), "malformed researcher"))
    val wrongKind = mutate(membershipJson)(_("groupId") = ujson.Str(workId.value))
    assert(Contract.decode[ResearcherGroupMembership](wrongKind).isLeft)
    assertRejected(
      researcherGroupMembership.copy(groupId = ResearcherId("researcher:v1:nothex")),
      "groupId: malformed stable ID"
    )
  }

  test("ResearcherGroupMembership: groupSize must be at least 1") {
    assertRejected(researcherGroupMembership.copy(groupSize = 0), "groupSize: 0 must be >= 1")
    assertRejected(
      researcherGroupMembership.copy(groupKind = GroupKind.Community, groupSize = -3),
      "groupSize: -3 must be >= 1"
    )
  }

  test("ResearcherGroupMembership: a component's groupId is its smallest member ID") {
    val notSmallest = researcherGroupMembership.copy(
      researcherId = ResearcherId(edgeLeft),
      groupId = ResearcherId(edgeRight)
    )
    assertRejected(notSmallest, "must be its smallest member ResearcherId")
    val singletonOther = researcherGroupMembership.copy(groupSize = 1)
    assertRejected(singletonOther, "a singleton component's groupId must equal researcherId")
  }

  test("ResearcherGroupMembership: an inverted window is rejected") {
    assertRejected(
      researcherGroupMembership.copy(window = YearWindow(2025, 2015)),
      "window: startYear 2025 exceeds endYear 2015"
    )
  }

  // ---- EvidenceItem shapes for Spark (schema unchanged) ----------------------------------

  val third: ResearcherId = ResearcherId.fromAnchorMention(MentionId.of(workId, 2))
  val pathIds: List[String] = List(third.value, sousa.value, campos.value)

  def sparkProvenance(ids: List[String]): Provenance =
    pipelineProvenance(graphRun, "spark.path-evidence", "graph-policy/1", ids)

  val pathEvidence: EvidenceItem = EvidenceItem(
    schemaVersion = 1,
    evidenceId = EvidenceId.of(EvidenceType.GraphPath, graphRun, pathIds),
    evidenceType = EvidenceType.GraphPath,
    subjectIds = pathIds,
    window = Some(window),
    metric = None,
    snippet = None,
    provenance = sparkProvenance(pathIds)
  )

  val secondWork: WorkId = WorkId.fromDblpKey("journals/fixture/Second23")
  val maxIds: List[String] = List(sousa.value, workId.value, secondWork.value)

  val maxWorksEvidence: EvidenceItem = EvidenceItem(
    schemaVersion = 1,
    evidenceId = EvidenceId.of(EvidenceType.Metric, graphRun, maxIds),
    evidenceType = EvidenceType.Metric,
    subjectIds = maxIds,
    window = Some(window),
    metric = Some(MetricValue(CollaborationMetric.MaxCoauthorsOnOnePaper, 2.0)),
    snippet = None,
    provenance = sparkProvenance(maxIds)
  )

  test("EvidenceItem: a graph_path of researcher IDs in path order is valid and round-trips") {
    assertEquals(Contract.validate(pathEvidence), Nil)
    assertEquals(roundTrip(pathEvidence), Right(pathEvidence))
    val json = ujson.read(Contract.encode(pathEvidence).toOption.get)
    assertEquals(json("evidenceType"), ujson.Str("graph_path"))
    assertEquals(json("subjectIds").arr.map(_.str).toList, pathIds)
  }

  test("EvidenceItem: path order is part of the evidence ID") {
    assertNotEquals(
      EvidenceId.of(EvidenceType.GraphPath, graphRun, pathIds),
      EvidenceId.of(EvidenceType.GraphPath, graphRun, pathIds.reverse)
    )
  }

  test("EvidenceItem: a graph_path without a window is rejected") {
    assertRejected(pathEvidence.copy(window = None), "window: required for graph_path evidence")
  }

  test("EvidenceItem: M_r works (researcher ID then work IDs) are valid metric evidence") {
    assertEquals(Contract.validate(maxWorksEvidence), Nil)
    assertEquals(roundTrip(maxWorksEvidence), Right(maxWorksEvidence))
    val json = ujson.read(Contract.encode(maxWorksEvidence).toOption.get)
    assertEquals(json("metric")("metric"), ujson.Str("max_coauthors_on_one_paper"))
    assertEquals(json("subjectIds").arr.map(_.str).toList, maxIds)
  }

  test("EvidenceItem: M_r evidence without its metric value is rejected") {
    assertRejected(maxWorksEvidence.copy(metric = None), "metric: required for metric evidence")
  }

  test("EvidenceItem: the evidence ID ignores window and metric (documented limitation)") {
    val other = maxWorksEvidence.copy(window = Some(YearWindow(2021, 2023)))
    assertEquals(Contract.validate(other), Nil)
    assertEquals(other.evidenceId, maxWorksEvidence.evidenceId)
  }
}
