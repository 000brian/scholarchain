package org.scholarchain.core

import Fixtures._

/** Each validation rule, exercised against an otherwise valid fixture. */
class ValidationSuite extends munit.FunSuite {

  def errorsOf[A: Validator](a: A): List[String] = Contract.validate(a)

  def assertRejected[A](a: A, fragment: String)(implicit
      v: Validator[A],
      loc: munit.Location
  ): Unit = {
    val errors = errorsOf(a)
    assert(
      errors.exists(_.contains(fragment)),
      s"expected an error containing '$fragment', got $errors"
    )
  }

  // ---- missing provenance ---------------------------------------------------------------
  test("missing (null) provenance is rejected, not defaulted") {
    assertRejected(workRecord.copy(provenance = null), "provenance: missing (null)")
    assertRejected(coauthorEdge.copy(provenance = null), "provenance: missing (null)")
    assertRejected(answerResponse.copy(provenance = null), "provenance: missing (null)")
  }

  test("null nested provenance inside a bundle item is rejected") {
    val bundle = evidenceBundle.copy(items = List(workEvidence.copy(provenance = null)))
    assertRejected(bundle, "items[0].provenance: missing (null)")
  }

  test("provenance fields are required and well-formed") {
    assertRejected(provenance.copy(sourceRecordIds = Nil), "sourceRecordIds: must not be empty")
    assertRejected(
      provenance.copy(sourceRecordIds = List(" ")),
      "sourceRecordIds[0]: must not be blank"
    )
    assertRejected(provenance.copy(datasetId = ""), "datasetId")
    assertRejected(provenance.copy(datasetId = "DBLP"), "datasetId")
    assertRejected(provenance.copy(snapshotId = " "), "snapshotId: must not be blank")
    assertRejected(provenance.copy(jobName = ""), "jobName: must not be blank")
    assertRejected(provenance.copy(policyVersion = ""), "policyVersion: must not be blank")
    assertRejected(provenance.copy(runId = RunId("run:v1:nope")), "runId: malformed stable ID")
    assertRejected(provenance.copy(contentHash = Some("md5:abc")), "contentHash")
  }

  // ---- confidence ranges ----------------------------------------------------------------
  test("confidence outside [0, 1] or NaN is rejected") {
    List(-0.01, 1.01, Double.NaN, Double.PositiveInfinity).foreach { c =>
      assertRejected(authorshipRecord.copy(matchConfidence = c), "matchConfidence: confidence")
      assertRejected(matchDecision.copy(confidence = c), "confidence: confidence")
      assertRejected(coauthorEdge.copy(minIdentityConfidence = c), "minIdentityConfidence")
      assertRejected(researcherMetrics.copy(identityConfidence = c), "identityConfidence")
    }
  }

  test("confidence bounds 0 and 1 are accepted") {
    assertEquals(errorsOf(authorshipRecord.copy(matchConfidence = 0.0)), Nil)
    assertEquals(errorsOf(authorshipRecord.copy(matchConfidence = 1.0)), Nil)
  }

  // ---- schema versions ------------------------------------------------------------------
  test("unsupported schema versions are rejected in memory") {
    assertRejected(workRecord.copy(schemaVersion = 0), "schemaVersion: unsupported version 0")
    assertRejected(workRecord.copy(schemaVersion = 2), "schemaVersion: unsupported version 2")
    assertRejected(
      workRecord.copy(provenance = provenance.copy(schemaVersion = 2)),
      "provenance.schemaVersion: unsupported version 2"
    )
  }

  // ---- malformed stable IDs -------------------------------------------------------------
  test("malformed stable IDs are rejected in memory") {
    assertRejected(workRecord.copy(workId = WorkId("work:v1:XYZ")), "workId: malformed stable ID")
    assertRejected(
      authorshipRecord.copy(researcherId = ResearcherId("r1")),
      "researcherId: malformed"
    )
    assertRejected(coauthorEdge.copy(edgeId = EdgeId("edge:v1:")), "edgeId: malformed")
    assertRejected(
      answerClaim.copy(evidenceIds = List(EvidenceId("evidence:v2:" + "0" * 32))),
      "evidenceIds[0]: malformed"
    )
    assertRejected(queryPlan.copy(graphRunId = RunId("")), "graphRunId: malformed")
  }

  test("well-formed IDs that do not match their formula are rejected") {
    assertRejected(workRecord.copy(dblpKey = "conf/sigir/Other23"), "workId: work:v1:")
    assertRejected(authorMention.copy(position = 1), "mentionId:")
    assertRejected(
      canonicalResearcher.copy(researcherId = campos),
      "researcherId: " + campos.value + " is not ResearcherId.fromAnchorMention"
    )
    assertRejected(canonicalResearcher.copy(anchorMentionId = camposMention), "researcherId:")
    assertRejected(workLink.copy(externalId = "W1"), "linkId:")
    assertRejected(coauthorEdge.copy(window = YearWindow(2015, 2024)), "edgeId:")
    assertRejected(workEvidence.copy(subjectIds = List("work:v1:" + "0" * 32)), "evidenceId:")
    assertRejected(evidenceBundle.copy(items = List(workEvidence)), "bundleId:")
  }

  test("graph node IDs must match the node label") {
    assertRejected(graphNode.copy(nodeId = workId.value), "nodeId: ")
    assertEquals(errorsOf(graphNode.copy(label = GraphNodeLabel.Work, nodeId = workId.value)), Nil)
  }

  // ---- undirected pair ordering ---------------------------------------------------------
  test("coauthor pair candidates out of canonical order are rejected") {
    val swapped = coauthorPairCandidate.copy(left = sousaName, right = jorgeName)
    assertRejected(swapped, "left/right: undirected pair")
  }

  test("coauthor pair candidates with equal names (self pairs) are rejected") {
    assertRejected(coauthorPairCandidate.copy(right = jorgeName), "left/right: undirected pair")
  }

  test("coauthor edges out of canonical order are rejected") {
    val swapped = coauthorEdge.copy(left = coauthorEdge.right, right = coauthorEdge.left)
    // The edge ID is order-independent, so the ordering rule alone rejects this record.
    assertEquals(errorsOf(swapped).size, 1, errorsOf(swapped))
    assertRejected(swapped, "left/right: undirected pair")
  }

  test("coauthor self edges are rejected") {
    assertRejected(coauthorEdge.copy(right = coauthorEdge.left), "left/right: undirected pair")
  }

  test("undirected graph edges out of canonical order are rejected; directed edges are not") {
    assertRejected(
      graphEdge.copy(from = graphEdge.to, to = graphEdge.from),
      "from/to: undirected pair"
    )
    val authored = graphEdge.copy(
      label = GraphEdgeLabel.Authored,
      from = sousa.value,
      to = workId.value,
      edgeId = EdgeId.of(GraphEdgeLabel.Authored, sousa.value, workId.value, window).toOption.get
    )
    assertEquals(errorsOf(authored), Nil)
  }

  test("directed graph edges must connect the labelled endpoint kinds") {
    val wrong = graphEdge.copy(
      label = GraphEdgeLabel.Authored,
      from = workId.value,
      to = sousa.value,
      edgeId = EdgeId.of(GraphEdgeLabel.Authored, workId.value, sousa.value, window).toOption.get
    )
    assertRejected(wrong, "from: ")
    assertRejected(wrong, "to: ")
  }

  // ---- windows --------------------------------------------------------------------------
  test("windows whose start year exceeds the end year are rejected") {
    val bad = YearWindow(2025, 2015)
    assertRejected(bad, "startYear 2025 exceeds endYear 2015")
    assertRejected(
      researcherMetrics.copy(window = bad),
      "window: startYear 2025 exceeds endYear 2015"
    )
    assertRejected(coauthorEdge.copy(window = bad), "window: startYear 2025 exceeds endYear 2015")
    assertRejected(graphEdge.copy(window = bad), "window: startYear 2025 exceeds endYear 2015")
    assertRejected(metricEvidence.copy(window = Some(bad)), "window: startYear 2025 exceeds")
    assertRejected(answerClaim.copy(window = Some(bad)), "window: startYear 2025 exceeds")
    val step = queryPlan.steps(1).copy(window = Some(bad))
    assertRejected(queryPlan.copy(steps = List(step)), "steps[0].window: startYear 2025 exceeds")
  }

  test("single-year windows are accepted") {
    assertEquals(errorsOf(YearWindow(2023, 2023)), Nil)
  }

  test("metrics state their window: graph evidence and graph queries require one") {
    assertRejected(metricEvidence.copy(window = None), "window: required for metric evidence")
    val step = queryPlan.steps(1).copy(window = None)
    assertRejected(
      queryPlan.copy(steps = List(step)),
      "steps[0].window: required for collaboration_breadth"
    )
  }

  test("edge years must lie inside the edge window") {
    assertRejected(coauthorEdge.copy(firstYear = 2014), "firstYear: 2014 is outside window")
    assertRejected(coauthorEdge.copy(firstYear = 2024, lastYear = 2023), "firstYear: 2024 exceeds")
  }

  // ---- other record rules ---------------------------------------------------------------
  test("external identifiers in records must be canonical") {
    assertRejected(workRecord.copy(doi = Some("10.1145/ABC")), "doi: ")
    assertRejected(authorMention.copy(orcid = Some("0000-0000-0000-0000")), "orcid: ")
    assertRejected(
      workLink.copy(externalId = "https://openalex.org/W1"),
      "externalId: 'https://openalex.org/W1' is not a canonical openalex_work identifier"
    )
    val badClaim = fromOpenAlex(ExternalIdType.OpenAlexAuthor, "a5080716895")
    assertRejected(
      canonicalResearcher.copy(externalIds = List(badClaim)),
      "externalIds[0].value: 'a5080716895' is not a canonical openalex_author"
    )
    assertRejected(authorMention.copy(name = "Hugo  O. Sousa"), "name: ")
  }

  test("match decisions: unresolved has no candidate; other outcomes need one") {
    assertRejected(
      matchDecision.copy(outcome = MatchOutcome.Unresolved),
      "candidateResearcherId: must be null when unresolved"
    )
    assertRejected(
      matchDecision.copy(candidateResearcherId = None),
      "candidateResearcherId: required"
    )
    assertEquals(
      errorsOf(matchDecision.copy(outcome = MatchOutcome.Unresolved, candidateResearcherId = None)),
      Nil
    )
  }

  test("text-bearing records carry the content hash of their text") {
    assertRejected(
      workEvidence.copy(snippet = Some("changed")),
      "contentHash: does not match snippet"
    )
    assertRejected(
      answerClaim.copy(provenance = answerClaim.provenance.copy(contentHash = None)),
      "contentHash: required"
    )
  }

  test("claims cite evidence present in the bundle") {
    val stray = EvidenceId.of(EvidenceType.Work, queryRun, List("work:v1:" + "1" * 32))
    val claim = answerClaim.copy(evidenceIds = List(stray))
    assertRejected(answerResponse.copy(claims = List(claim)), "is not in bundle")
    assertRejected(
      answerClaim.copy(evidenceIds = Nil),
      "evidenceIds: a verified claim must cite evidence"
    )
  }

  test("counts must agree with their lists") {
    assertRejected(publicationTeam.copy(teamSize = 4), "teamSize: 4 != authorNames size 3")
    assertRejected(
      coauthorEdge.copy(sharedWorkCount = 2),
      "sharedWorkCount: 2 != supportingWorkIds"
    )
    assertRejected(coauthorPairCandidate.copy(teamSize = 1), "teamSize: 1 must be >= 2")
  }

  test("metric values are finite, non-negative, and not repeated") {
    val dup = researcherMetrics.values.head
    assertRejected(
      researcherMetrics.copy(values = List(dup, dup)),
      "values.metric: contains duplicates"
    )
    assertRejected(
      researcherMetrics.copy(values = List(dup.copy(value = -1.0))),
      "values[0].value(distinct_coauthors)"
    )
  }

  test("encode refuses invalid records") {
    val result = Contract.encode(workRecord.copy(schemaVersion = 9))
    assert(result.isLeft, result)
  }

  // ---- review finding 1: researcher identity anchor ---------------------------------------
  test("researchers split from one dblp name are distinct valid records with distinct IDs") {
    val m1 = MentionId.of(WorkId.fromDblpKey("conf/x/WangL19"), 0)
    val m2 = MentionId.of(WorkId.fromDblpKey("conf/z/ChenW20"), 1)
    def weiWang(anchor: MentionId) = canonicalResearcher.copy(
      researcherId = ResearcherId.fromAnchorMention(anchor),
      anchorMentionId = anchor,
      displayName = "Wei Wang",
      dblpNames = List("Wei Wang"),
      externalIds = Nil
    )
    val (a, b) = (weiWang(m1), weiWang(m2))
    assertEquals(errorsOf(a), Nil)
    assertEquals(errorsOf(b), Nil)
    assertNotEquals(a.researcherId, b.researcherId)
  }

  test("researcher anchorMentionId must be a well-formed mention ID") {
    assertRejected(
      canonicalResearcher.copy(anchorMentionId = MentionId("mention:v1:x")),
      "anchorMentionId: malformed stable ID"
    )
  }

  // ---- review finding 2: every foreign identifier names its source snapshot ---------------
  test("the external-identifier link fixtures are valid") {
    List(externalIdLink, workLink, venueLink, institutionLink).foreach(l =>
      assertEquals(errorsOf(l), Nil, l)
    )
  }

  test("an OpenAlex ID linked with dblp (or any non-OpenAlex) provenance is rejected") {
    List(SourceType.Dblp, SourceType.Crossref, SourceType.Orcid, SourceType.Pipeline).foreach {
      st =>
        val p = externalIdLink.provenance.copy(sourceType = st)
        val l = externalIdLink.copy(
          provenance = p,
          linkId = LinkId.of(
            l0.subjectKind,
            l0.subjectId,
            l0.idType,
            l0.externalId,
            st,
            p.datasetId,
            p.snapshotId
          )
        )
        assertRejected(
          l,
          s"provenance.sourceType: a openalex_author identifier cannot be asserted by source ${st.wire}"
        )
    }
  }
  private def l0 = externalIdLink

  test("an ORCID iD linked from an enrichment source is accepted; a dblp-sourced link is not") {
    def orcidLink(st: SourceType, dataset: String) = {
      val p = externalIdLink.provenance.copy(sourceType = st, datasetId = dataset)
      val id = "0000-0003-3226-9189"
      externalIdLink.copy(
        idType = ExternalIdType.Orcid,
        externalId = id,
        provenance = p,
        linkId = LinkId.of(
          LinkSubjectKind.Mention,
          l0.subjectId,
          ExternalIdType.Orcid,
          id,
          st,
          dataset,
          p.snapshotId
        )
      )
    }
    assertEquals(errorsOf(orcidLink(SourceType.OpenAlex, "openalex")), Nil)
    assertEquals(errorsOf(orcidLink(SourceType.Crossref, "crossref")), Nil)
    assertRejected(
      orcidLink(SourceType.Dblp, "dblp"),
      "dblp-published identifiers are carried on WorkRecord.doi and AuthorMention.orcid"
    )
  }

  test("the link ID binds the asserting snapshot: changing the snapshot needs a new link ID") {
    val moved = externalIdLink.copy(
      provenance = externalIdLink.provenance.copy(snapshotId = "2026-10-14")
    )
    assertRejected(moved, "linkId: ")
  }

  test("link identifier kinds must fit the subject kind and subject ID") {
    assertRejected(
      workLink.copy(subjectKind = LinkSubjectKind.Mention),
      "idType: openalex_work cannot be linked to a mention"
    )
    assertRejected(
      workLink.copy(subjectId = "conf/sigir"),
      "subjectId: 'conf/sigir' is not a work ID"
    )
    assertRejected(venueLink.copy(subjectId = "conf"), "subjectId: 'conf' is not a dblp venue key")
  }

  test("dblp-published records must have dblp provenance (their DOI and ORCID are dblp's)") {
    val oa = openAlexProvenance(List(openAlexWork))
    assertRejected(workRecord.copy(provenance = oa), "provenance.sourceType: WorkRecord carries")
    assertRejected(authorMention.copy(provenance = oa), "provenance.sourceType: AuthorMention")
    assertRejected(venue.copy(provenance = oa), "provenance.sourceType: Venue carries")
    // The dblp-published ORCID on a mention is legitimate with dblp provenance.
    assertEquals(errorsOf(authorMention), Nil)
  }

  test("an institution keyed by an OpenAlex ID must have OpenAlex provenance") {
    assertRejected(
      institution.copy(provenance = dblpProvenance(List(dblpKey))),
      "provenance.sourceType: a openalex_institution identifier cannot be asserted by source dblp"
    )
  }

  test("researcher external IDs must name a source that can assert them, with a snapshot") {
    val oaByDblp = SourcedExternalId(
      ExternalIdType.OpenAlexAuthor,
      "A5080716895",
      SourceType.Dblp,
      "dblp",
      "2026-11-01",
      dblpKey
    )
    assertRejected(
      canonicalResearcher.copy(externalIds = List(oaByDblp)),
      "externalIds[0].sourceType: a openalex_author identifier cannot be asserted by source dblp"
    )
    val viaPipeline = fromOpenAlex(ExternalIdType.OpenAlexAuthor, "A5080716895")
      .copy(sourceType = SourceType.Pipeline)
    assertRejected(
      canonicalResearcher.copy(externalIds = List(viaPipeline)),
      "externalIds[0].sourceType: a openalex_author identifier cannot be asserted by source pipeline"
    )
    val noSnapshot =
      fromOpenAlex(ExternalIdType.OpenAlexAuthor, "A5080716895").copy(snapshotId = " ")
    assertRejected(
      canonicalResearcher.copy(externalIds = List(noSnapshot)),
      "externalIds[0].snapshotId: must not be blank"
    )
    val noRecord =
      fromOpenAlex(ExternalIdType.OpenAlexAuthor, "A5080716895").copy(sourceRecordId = "")
    assertRejected(
      canonicalResearcher.copy(externalIds = List(noRecord)),
      "externalIds[0].sourceRecordId: must not be blank"
    )
    val badDataset = fromOpenAlex(ExternalIdType.OpenAlexAuthor, "A5080716895").copy(datasetId = "")
    assertRejected(
      canonicalResearcher.copy(externalIds = List(badDataset)),
      "externalIds[0].datasetId"
    )
  }

  test("a dblp-published ORCID on a researcher keeps dblp provenance") {
    val dblpOrcid = canonicalResearcher.externalIds.filter(_.sourceType == SourceType.Dblp)
    assertEquals(dblpOrcid.map(_.idType), List(ExternalIdType.Orcid))
    assertEquals(errorsOf(canonicalResearcher.copy(externalIds = dblpOrcid)), Nil)
  }

  test("researcher external IDs: allowed kinds, sorted, distinct, at most one ORCID iD") {
    val doi = fromOpenAlex(ExternalIdType.Doi, "10.1145/3539618.3591892")
    assertRejected(
      canonicalResearcher.copy(externalIds = List(doi)),
      "externalIds[0].idType: doi is not a researcher identifier"
    )
    assertRejected(
      canonicalResearcher.copy(externalIds = canonicalResearcher.externalIds.reverse),
      "externalIds: must be distinct and sorted"
    )
    val first = canonicalResearcher.externalIds.head
    assertRejected(
      canonicalResearcher.copy(externalIds = List(first, first)),
      "externalIds: must be distinct and sorted"
    )
    val twoOrcids = List(
      fromOpenAlex(ExternalIdType.Orcid, "0000-0001-8209-698X"),
      fromOpenAlex(ExternalIdType.Orcid, "0000-0003-3226-9189")
    )
    assertRejected(
      canonicalResearcher.copy(externalIds = twoOrcids),
      "externalIds: at most one ORCID iD per researcher"
    )
  }
}
