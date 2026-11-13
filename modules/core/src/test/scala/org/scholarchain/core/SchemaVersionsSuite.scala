package org.scholarchain.core

/** Frozen schema-version table and enum wire names. Editing these expectations is a contract change
  * and must be called out (and versioned when breaking).
  */
class SchemaVersionsSuite extends munit.FunSuite {

  test("schema version table is frozen at v1") {
    assertEquals(
      SchemaVersions.current.toList,
      List(
        "Provenance",
        "WorkRecord",
        "AuthorMention",
        "ExternalIdLink",
        "CanonicalResearcher",
        "AuthorshipRecord",
        "PublicationTeam",
        "CoauthorPairCandidate",
        "CoauthorEdge",
        "ResearcherMetrics",
        "Venue",
        "Institution",
        "Topic",
        "MatchDecision",
        "GraphNode",
        "GraphEdge",
        "EvidenceItem",
        "EvidenceBundle",
        "QueryPlan",
        "AnswerClaim",
        "AnswerResponse",
        "ScholarChainConfig"
      ).map(_ -> 1)
    )
  }

  test("only the current version is supported; unknown schemas are unsupported") {
    assert(SchemaVersions.isSupported("WorkRecord", 1))
    assert(!SchemaVersions.isSupported("WorkRecord", 0))
    assert(!SchemaVersions.isSupported("WorkRecord", 2))
    assert(!SchemaVersions.isSupported("NoSuchRecord", 1))
    intercept[IllegalArgumentException](SchemaVersions.currentOf("NoSuchRecord"))
  }

  test("enum wire names are frozen") {
    assertEquals(
      SourceType.values.map(_.wire),
      List("dblp", "openalex", "crossref", "orcid", "pipeline", "runtime")
    )
    assertEquals(
      MatchOutcome.values.map(_.wire),
      List("match", "possible_match", "non_match", "unresolved")
    )
    assertEquals(
      EvidenceType.values.map(_.wire),
      List(
        "work",
        "authorship",
        "coauthor_edge",
        "graph_path",
        "metric",
        "match_decision",
        "external_record"
      )
    )
    assertEquals(
      CollaborationMetric.values.map(_.wire),
      List(
        "distinct_coauthors",
        "coauthored_works",
        "weighted_degree",
        "betweenness_centrality",
        "bridge_score",
        "component_size",
        "clustering_coefficient"
      )
    )
    assertEquals(
      QueryOperation.values.map(_.wire),
      List(
        "researcher_lookup",
        "collaboration_breadth",
        "coauthor_neighborhood",
        "shortest_path",
        "connected_component",
        "bridge_researchers"
      )
    )
    assertEquals(
      VerificationStatus.values.map(_.wire),
      List("verified", "partially_verified", "unsupported", "contradicted")
    )
    assertEquals(
      GraphNodeLabel.values.map(_.wire),
      List("researcher", "work", "venue", "institution", "topic")
    )
    assertEquals(
      GraphEdgeLabel.values.map(_.wire),
      List("coauthored_with", "authored", "published_in", "affiliated_with", "has_topic")
    )
    assertEquals(GraphEdgeLabel.values.filter(_.undirected), List(GraphEdgeLabel.CoauthoredWith))
    assertEquals(
      ExternalIdType.values.map(_.wire),
      List(
        "doi",
        "orcid",
        "openalex_work",
        "openalex_author",
        "openalex_source",
        "openalex_institution"
      )
    )
    assertEquals(LinkSubjectKind.values.map(_.wire), List("work", "mention", "venue"))
  }

  test("enum wire names round-trip and unknown names are rejected") {
    SourceType.values.foreach(v => assertEquals(SourceType.fromWire(v.wire), Right(v)))
    assert(MatchOutcome.fromWire("MATCH").isLeft)
    assertEquals(
      upickle.default.write[QueryOperation](QueryOperation.ShortestPath),
      "\"shortest_path\""
    )
  }

  test("external ID kinds name the sources allowed to assert them") {
    import SourceType._
    assertEquals(ExternalIdType.Doi.sources, Set[SourceType](Dblp, OpenAlex, Crossref, Orcid))
    assertEquals(ExternalIdType.Orcid.sources, Set[SourceType](Dblp, OpenAlex, Crossref, Orcid))
    List(
      ExternalIdType.OpenAlexWork,
      ExternalIdType.OpenAlexAuthor,
      ExternalIdType.OpenAlexSource,
      ExternalIdType.OpenAlexInstitution
    ).foreach(t => assertEquals(t.sources, Set[SourceType](OpenAlex), t.wire))
  }
}
