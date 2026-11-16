package org.scholarchain.core

/** Frozen schema-version table and enum wire names. Editing these expectations is a contract change
  * and must be called out (and versioned when breaking).
  */
class SchemaVersionsSuite extends munit.FunSuite {

  test("schema version table is frozen") {
    assertEquals(
      SchemaVersions.current.toList,
      List(
        "Provenance" -> 1,
        "WorkRecord" -> 2, // v2: eeLinks, urls
        "AuthorMention" -> 1,
        "ExternalIdLink" -> 1,
        "CanonicalResearcher" -> 1,
        "AuthorshipRecord" -> 1,
        "PublicationTeam" -> 1,
        "CoauthorPairCandidate" -> 2, // v2: positions
        "CoauthorEdge" -> 1,
        "ResearcherMetrics" -> 1,
        "ResearcherGroupMembership" -> 1, // new in Phase 6
        "Venue" -> 1,
        "Institution" -> 1,
        "Topic" -> 1,
        "MatchCandidate" -> 1, // new in Phase 4
        "MatchDecision" -> 2, // v2 in Phase 5: subjects, path, rules, reasons, versions, time
        "LlmAdjudication" -> 1, // new in Phase 5
        "GraphNode" -> 1,
        "GraphEdge" -> 1,
        "EvidenceItem" -> 1,
        "EvidenceBundle" -> 1,
        "QueryPlan" -> 1,
        "AnswerClaim" -> 2, // v2 in Phase 9: claimId, uncertainty, new status wire names
        "AnswerResponse" -> 2, // v2 in Phase 9: abstained, abstentionReason, optional bundle
        "ScholarChainConfig" -> 1,
        "SemanticDocument" -> 1, // new in Phase 7
        "VectorMetadata" -> 1, // new in Phase 7
        "QueryAudit" -> 1 // new in Phase 9
      )
    )
  }

  test("only the current version is supported; unknown schemas are unsupported") {
    assert(SchemaVersions.isSupported("WorkRecord", 2))
    assert(!SchemaVersions.isSupported("WorkRecord", 0))
    assert(!SchemaVersions.isSupported("WorkRecord", 1))
    assert(!SchemaVersions.isSupported("WorkRecord", 3))
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
      List("accepted", "rejected", "unresolved", "conflict")
    )
    assertEquals(
      MatchPath.values.map(_.wire),
      List("exact_id", "deterministic", "probabilistic", "llm")
    )
    assertEquals(AdjudicationOutcome.values.map(_.wire), List("accept", "reject", "abstain"))
    assertEquals(
      EvidenceType.values.map(_.wire),
      List(
        "work",
        "authorship",
        "coauthor_edge",
        "graph_path",
        "metric",
        "match_decision",
        "external_record",
        // Phase 9 addition (additive; the names above are unchanged)
        "semantic_context"
      )
    )
    assertEquals(
      EvidenceType.values.filter(EvidenceType.requiresWindow).map(_.wire),
      List("coauthor_edge", "graph_path", "metric")
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
        "clustering_coefficient",
        // Phase 6 additions (additive; the names above are unchanged)
        "publication_count",
        "total_coauthor_occurrences",
        "average_coauthors_per_paper",
        "max_coauthors_on_one_paper",
        "community_size",
        "participation_coefficient",
        "cross_community_degree",
        "communities_connected",
        "coreness"
      )
    )
    assertEquals(GroupKind.values.map(_.wire), List("component", "community"))
    assertEquals(
      QueryOperation.values.map(_.wire),
      List(
        "researcher_lookup",
        "collaboration_breadth",
        "coauthor_neighborhood",
        "shortest_path",
        "connected_component",
        "bridge_researchers",
        // Phase 9 additions (additive; the names above are unchanged)
        "researcher_ranking",
        "max_team_size",
        "community_analysis",
        "temporal_comparison",
        "semantic_context"
      )
    )
    assertEquals(
      QueryOperation.values.filterNot(QueryOperation.requiresWindow).map(_.wire),
      List("researcher_lookup")
    )
    assertEquals(
      QueryOperation.values.filter(QueryOperation.mayOmitResearchers).map(_.wire),
      List("bridge_researchers", "researcher_ranking", "semantic_context")
    )
    // Phase 9 (breaking, with AnswerClaim v2): partially_verified -> partially_supported,
    // contradicted -> conflicting.
    assertEquals(
      VerificationStatus.values.map(_.wire),
      List("verified", "partially_supported", "unsupported", "conflicting")
    )
    assertEquals(PlanSource.values.map(_.wire), List("llm", "rule"))
    assertEquals(config.LlmProvider.values.map(_.wire), List("anthropic", "bedrock", "fake"))
    assertEquals(
      GraphNodeLabel.values.map(_.wire),
      List(
        "researcher",
        "work",
        "venue",
        "institution",
        "topic",
        // Phase 7 additions (additive; the names above are unchanged)
        "author_mention",
        "dataset_snapshot",
        "text_chunk"
      )
    )
    assertEquals(
      GraphEdgeLabel.values.map(_.wire),
      List(
        "coauthored_with",
        "authored",
        "published_in",
        "affiliated_with",
        "has_topic",
        // Phase 7 additions (additive; the names above are unchanged)
        "resolves_to",
        "derived_from",
        "supported_by"
      )
    )
    assertEquals(GraphEdgeLabel.values.filter(_.undirected), List(GraphEdgeLabel.CoauthoredWith))
    assertEquals(
      GraphEdgeLabel.values.map(l => (l.wire, l.fromLabel.wire, l.toLabel.wire)),
      List(
        ("coauthored_with", "researcher", "researcher"),
        ("authored", "researcher", "work"),
        ("published_in", "work", "venue"),
        ("affiliated_with", "researcher", "institution"),
        ("has_topic", "work", "topic"),
        ("resolves_to", "author_mention", "researcher"),
        ("derived_from", "work", "dataset_snapshot"),
        ("supported_by", "researcher", "text_chunk")
      )
    )
    assertEquals(
      SemanticDocumentType.values.map(_.wire),
      List(
        "researcher_profile",
        "community_summary",
        "collaboration_path",
        "paper_summary",
        "topic_institution_summary"
      )
    )
    assertEquals(
      SemanticDocumentType.values.filter(SemanticDocumentType.requiresWindow).map(_.wire),
      List("researcher_profile", "community_summary", "collaboration_path")
    )
    assertEquals(
      config.EmbeddingProvider.values.map(_.wire),
      List("voyage", "bedrock", "fake")
    )
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
    assertEquals(
      MatchSubjectKind.values.map(_.wire),
      List("mention_pair", "mention_researcher")
    )
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
