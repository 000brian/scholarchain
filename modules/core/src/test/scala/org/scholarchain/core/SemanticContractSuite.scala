package org.scholarchain.core

import Fixtures._

/** Phase 7 contracts: new graph labels, ChunkId, SemanticDocument and VectorMetadata. Runs under
  * Scala 3 and Scala 2.13.
  */
class SemanticContractSuite extends munit.FunSuite {

  private def right[A](e: Either[String, A]): A = e.fold(m => fail(m), identity)

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

  def decodeError[A](json: String, fragment: String)(implicit
      rw: upickle.default.ReadWriter[A],
      v: Validator[A],
      loc: munit.Location
  ): Unit = {
    val result = Contract.decode[A](json)
    assert(result.left.exists(_.messages.exists(_.contains(fragment))), result)
  }

  def mutate(json: String)(f: ujson.Value => Unit): String = {
    val tree = ujson.read(json)
    f(tree)
    tree.render()
  }

  lazy val documentJson: String =
    Contract.encode(semanticDocument).fold(e => fail(e.message), identity)
  lazy val metadataJson: String =
    Contract.encode(vectorMetadata).fold(e => fail(e.message), identity)

  val snapshotNode = "dblp:2026-11-01"
  val graphProv: Provenance =
    pipelineProvenance(graphRun, "spark.build-graph", "graph-policy/1", List(dblpKey))

  def node(label: GraphNodeLabel, id: String): GraphNode =
    GraphNode(1, id, label, "fixture node", Map.empty, graphProv)

  def edge(label: GraphEdgeLabel, from: String, to: String): GraphEdge =
    GraphEdge(
      1,
      EdgeId.of(label, from, to, window).fold(m => fail(m), identity),
      label,
      from,
      to,
      window,
      None,
      graphProv
    )

  // ---- ChunkId ---------------------------------------------------------------------------

  // Golden values computed independently with `printf '%s' '<canonical encoding>' | shasum -a 256`.
  test("golden: ChunkId.of with a window label") {
    assertEquals(
      right(
        ChunkId.of(
          SemanticDocumentType.ResearcherProfile,
          "researcher:v1:6d39fb31017461bdf6bbe118864d1809",
          Some("2015-2025"),
          0,
          "sha256:ab554d00fa490bb4c222d4e70aad364bd6defba0d7722e950fc73a356c594bed"
        )
      ).value,
      "chunk:v1:794b0bd5791f0c269f919eae558f2cef"
    )
  }

  test("golden: ChunkId.of without a window uses '-' and the ordinal in decimal") {
    assertEquals(
      right(
        ChunkId.of(
          SemanticDocumentType.PaperSummary,
          "researcher:v1:6d39fb31017461bdf6bbe118864d1809",
          None,
          13,
          "sha256:ab554d00fa490bb4c222d4e70aad364bd6defba0d7722e950fc73a356c594bed"
        )
      ).value,
      "chunk:v1:" + Hashing.digest32(
        Seq(
          "chunk/v1",
          "paper_summary",
          "researcher:v1:6d39fb31017461bdf6bbe118864d1809",
          "-",
          "13",
          "sha256:ab554d00fa490bb4c222d4e70aad364bd6defba0d7722e950fc73a356c594bed"
        )
      )
    )
    assertEquals(
      right(
        ChunkId.of(
          SemanticDocumentType.PaperSummary,
          "researcher:v1:6d39fb31017461bdf6bbe118864d1809",
          None,
          3,
          "sha256:ab554d00fa490bb4c222d4e70aad364bd6defba0d7722e950fc73a356c594bed"
        )
      ).value,
      "chunk:v1:648f18f4129a967fa69d7eda636b5c01"
    )
  }

  test("ChunkId.forSubjects joins subject IDs with ',' in their stated order") {
    val ids = List(sousa.value, campos.value)
    assertEquals(ChunkId.subjectKeyOf(ids), Right(s"${sousa.value},${campos.value}"))
    assertEquals(
      ChunkId.forSubjects(SemanticDocumentType.CollaborationPath, ids, Some("x"), 0, profileHash),
      ChunkId.of(
        SemanticDocumentType.CollaborationPath,
        s"${sousa.value},${campos.value}",
        Some("x"),
        0,
        profileHash
      )
    )
    assertNotEquals(
      ChunkId.forSubjects(SemanticDocumentType.CollaborationPath, ids, Some("x"), 0, profileHash),
      ChunkId.forSubjects(
        SemanticDocumentType.CollaborationPath,
        ids.reverse,
        Some("x"),
        0,
        profileHash
      )
    )
    assertEquals(profileChunk.value, "chunk:v1:794b0bd5791f0c269f919eae558f2cef")
  }

  test("every ChunkId input changes the ID; repeated calls give the same ID") {
    val base = right(
      ChunkId.of(SemanticDocumentType.ResearcherProfile, "s", Some("w"), 0, profileHash)
    )
    assertEquals(
      right(ChunkId.of(SemanticDocumentType.ResearcherProfile, "s", Some("w"), 0, profileHash)),
      base
    )
    val variants = List(
      ChunkId.of(SemanticDocumentType.CommunitySummary, "s", Some("w"), 0, profileHash),
      ChunkId.of(SemanticDocumentType.ResearcherProfile, "t", Some("w"), 0, profileHash),
      ChunkId.of(SemanticDocumentType.ResearcherProfile, "s", Some("v"), 0, profileHash),
      ChunkId.of(SemanticDocumentType.ResearcherProfile, "s", None, 0, profileHash),
      ChunkId.of(SemanticDocumentType.ResearcherProfile, "s", Some("w"), 1, profileHash),
      ChunkId.of(
        SemanticDocumentType.ResearcherProfile,
        "s",
        Some("w"),
        0,
        ContentHash.of("other text")
      )
    ).map(right)
    variants.foreach(v => assertNotEquals(v, base))
    assertEquals(variants.distinct.size, variants.size)
  }

  test("ChunkId rejects blank subjects, bad window labels, negative ordinals, bad hashes") {
    val t = SemanticDocumentType.ResearcherProfile
    assert(ChunkId.of(t, " ", None, 0, profileHash).isLeft)
    assert(ChunkId.of(t, "s", Some("-"), 0, profileHash).isLeft)
    assert(ChunkId.of(t, "s", Some(""), 0, profileHash).isLeft)
    assert(ChunkId.of(t, "s", Some("2015 2025"), 0, profileHash).isLeft)
    assert(ChunkId.of(t, "s", None, -1, profileHash).isLeft)
    assert(ChunkId.of(t, "s", None, 0, "sha256:abc").isLeft)
    assert(ChunkId.of(t, "s", None, 0, Hashing.sha256Hex("x")).isLeft)
    assert(ChunkId.subjectKeyOf(Nil).isLeft)
    assert(ChunkId.subjectKeyOf(List("a,b")).isLeft)
    assert(ChunkId.subjectKeyOf(List("a", " ")).isLeft)
  }

  test("ChunkId parse accepts only chunk:v1:<32 lowercase hex>") {
    assertEquals(ChunkId.parse(profileChunk.value), Right(profileChunk))
    List(
      "chunk:v1:794B0BD5791F0C269F919EAE558F2CEF",
      "chunk:v2:794b0bd5791f0c269f919eae558f2cef",
      "chunk:v1:794b0bd5",
      "work:v1:794b0bd5791f0c269f919eae558f2cef",
      ""
    ).foreach(s => assert(ChunkId.parse(s).isLeft, s))
  }

  // ---- graph labels ----------------------------------------------------------------------

  test("author_mention nodes need a mention ID") {
    assertEquals(errorsOf(node(GraphNodeLabel.AuthorMention, sousaMention.value)), Nil)
    assertRejected(node(GraphNodeLabel.AuthorMention, sousa.value), "is not a mention ID")
    assertRejected(node(GraphNodeLabel.AuthorMention, "mention:v1:xyz"), "is not a mention ID")
  }

  test("dataset_snapshot nodes need a non-empty ID without whitespace") {
    assertEquals(errorsOf(node(GraphNodeLabel.DatasetSnapshot, snapshotNode)), Nil)
    assertRejected(node(GraphNodeLabel.DatasetSnapshot, ""), "must be non-empty, no whitespace")
    assertRejected(
      node(GraphNodeLabel.DatasetSnapshot, "dblp 2026-11-01"),
      "must be non-empty, no whitespace"
    )
  }

  test("text_chunk nodes need a chunk ID") {
    assertEquals(errorsOf(node(GraphNodeLabel.TextChunk, profileChunk.value)), Nil)
    assertRejected(node(GraphNodeLabel.TextChunk, sousa.value), "is not a chunk ID")
    assertRejected(node(GraphNodeLabel.TextChunk, "chunk:v1:NOT-HEX"), "is not a chunk ID")
  }

  test("new node labels round-trip on the wire; unknown labels are rejected") {
    List(
      node(GraphNodeLabel.AuthorMention, sousaMention.value),
      node(GraphNodeLabel.DatasetSnapshot, snapshotNode),
      node(GraphNodeLabel.TextChunk, profileChunk.value)
    ).foreach { n =>
      val json = Contract.encode(n).fold(e => fail(e.message), identity)
      assertEquals(ujson.read(json)("label"), ujson.Str(n.label.wire))
      assertEquals(Contract.decode[GraphNode](json), Right(n))
    }
    val json = Contract.encode(node(GraphNodeLabel.TextChunk, profileChunk.value)).toOption.get
    decodeError[GraphNode](
      mutate(json)(_("label") = ujson.Str("chunk")),
      "unknown graph node label 'chunk'"
    )
  }

  test("resolves_to, derived_from, supported_by edges: endpoints, direction, IDs") {
    val resolves = edge(GraphEdgeLabel.ResolvesTo, sousaMention.value, sousa.value)
    val derived = edge(GraphEdgeLabel.DerivedFrom, workId.value, snapshotNode)
    val supported = edge(GraphEdgeLabel.SupportedBy, sousa.value, profileChunk.value)
    List(resolves, derived, supported).foreach { e =>
      assertEquals(errorsOf(e), Nil)
      assert(!e.label.undirected)
      val json = Contract.encode(e).fold(err => fail(err.message), identity)
      assertEquals(Contract.decode[GraphEdge](json), Right(e))
    }
    // Directed: swapping the endpoints gives a different edge ID and fails the endpoint rules.
    assertNotEquals(
      EdgeId.of(GraphEdgeLabel.ResolvesTo, sousaMention.value, sousa.value, window),
      EdgeId.of(GraphEdgeLabel.ResolvesTo, sousa.value, sousaMention.value, window)
    )
    assertRejected(
      edge(GraphEdgeLabel.ResolvesTo, sousa.value, sousaMention.value),
      "from: '" + sousa.value + "' is not a mention ID"
    )
    assertRejected(
      edge(GraphEdgeLabel.DerivedFrom, workId.value, "dblp 2026"),
      "to: 'dblp 2026' must be non-empty, no whitespace"
    )
    assertRejected(
      edge(GraphEdgeLabel.SupportedBy, sousa.value, workId.value),
      "is not a chunk ID"
    )
    assertRejected(
      supported.copy(edgeId =
        edge(GraphEdgeLabel.SupportedBy, campos.value, profileChunk.value).edgeId
      ),
      "is not EdgeId.of(label, from, to, window)"
    )
  }

  // ---- SemanticDocument ------------------------------------------------------------------

  test("SemanticDocument: the fixture's text hash is its provenance content hash") {
    assertEquals(semanticDocument.provenance.contentHash, Some(ContentHash.of(profileText)))
    assertEquals(
      ChunkId.forSubjects(
        semanticDocument.documentType,
        semanticDocument.subjectIds,
        Some(windowLabel),
        0,
        ContentHash.of(semanticDocument.text)
      ),
      Right(semanticDocument.documentId)
    )
  }

  test("SemanticDocument: a contentHash that does not match the text is rejected") {
    assertRejected(
      semanticDocument.copy(text = profileText + " Edited."),
      "provenance.contentHash: does not match text"
    )
    assertRejected(
      semanticDocument.copy(provenance = semanticDocument.provenance.copy(contentHash = None)),
      "provenance.contentHash: required because the record carries text"
    )
    decodeError[SemanticDocument](
      mutate(documentJson)(_("text") = ujson.Str("Different text.")),
      "provenance.contentHash: does not match text"
    )
  }

  test("SemanticDocument: a bad indexName is rejected") {
    List(
      "Researcher-Profile-v1",
      "ab",
      "-researcher",
      "researcher-",
      "researcher_profile",
      "researcher profile",
      "a" * 64
    ).foreach { n =>
      assertRejected(semanticDocument.copy(indexName = n), "indexName: '")
      decodeError[SemanticDocument](
        mutate(documentJson)(_("indexName") = ujson.Str(n)),
        "indexName"
      )
    }
    List("abc", "a.b-c", "a" * 63, "collaboration-community-v1").foreach { n =>
      assertEquals(errorsOf(semanticDocument.copy(indexName = n)), Nil, n)
    }
  }

  test("SemanticDocument: vectorKey must be non-blank and at most 1024 UTF-8 bytes") {
    assertEquals(errorsOf(semanticDocument.copy(vectorKey = "k" * 1024)), Nil)
    assertRejected(semanticDocument.copy(vectorKey = "k" * 1025), "vectorKey: 1025 UTF-8 bytes")
    // 342 three-byte characters: 342 characters but 1026 UTF-8 bytes.
    val wide = "€" * 342
    assertEquals(wide.length, 342)
    assertRejected(semanticDocument.copy(vectorKey = wide), "vectorKey: 1026 UTF-8 bytes")
    assertEquals(errorsOf(semanticDocument.copy(vectorKey = "€" * 341)), Nil)
    assertRejected(semanticDocument.copy(vectorKey = " "), "vectorKey: must not be blank")
    decodeError[SemanticDocument](
      mutate(documentJson)(_("vectorKey") = ujson.Str("k" * 1025)),
      "vectorKey: 1025 UTF-8 bytes exceeds 1024"
    )
  }

  test("SemanticDocument: malformed documentId or sourceEvidenceId is rejected on decode") {
    decodeError[SemanticDocument](
      mutate(documentJson)(_("documentId") = ujson.Str("chunk:v1:NOT-HEX")),
      "malformed chunk ID 'chunk:v1:NOT-HEX'"
    )
    decodeError[SemanticDocument](
      mutate(documentJson)(_("documentId") = ujson.Str(sousa.value)),
      "malformed chunk ID"
    )
    decodeError[SemanticDocument](
      mutate(documentJson)(_("sourceEvidenceId") = ujson.Str("evidence:v1:zz")),
      "malformed evidence ID"
    )
    decodeError[SemanticDocument](
      mutate(documentJson)(_("documentType") = ujson.Str("profile")),
      "unknown semantic document type 'profile'"
    )
  }

  test("SemanticDocument: subjects per document type") {
    assertRejected(semanticDocument.copy(subjectIds = Nil), "subjectIds: must not be empty")
    assertRejected(
      semanticDocument.copy(subjectIds = List(sousa.value, campos.value)),
      "researcher_profile needs exactly 1"
    )
    assertRejected(semanticDocument.copy(subjectIds = List(workId.value)), "is not a researcher ID")
    val community = semanticDocument.copy(
      documentType = SemanticDocumentType.CommunitySummary,
      subjectIds = List(sousa.value, sousa.value, campos.value)
    )
    assertEquals(errorsOf(community), Nil)
    val path = semanticDocument.copy(documentType = SemanticDocumentType.CollaborationPath)
    assertRejected(path, "collaboration_path needs at least 2")
    assertEquals(errorsOf(path.copy(subjectIds = List(sousa.value, campos.value))), Nil)
    val paper = semanticDocument.copy(
      documentType = SemanticDocumentType.PaperSummary,
      subjectIds = List(workId.value, sousa.value),
      window = None
    )
    assertEquals(errorsOf(paper), Nil)
    assertRejected(paper.copy(subjectIds = List(sousa.value)), "subjectIds[0]: ")
    val topic = semanticDocument.copy(
      documentType = SemanticDocumentType.TopicInstitutionSummary,
      subjectIds = List("T10101"),
      window = None
    )
    assertEquals(errorsOf(topic), Nil)
    assertRejected(topic.copy(subjectIds = List(" ")), "subjectIds[0]: must not be blank")
  }

  test("SemanticDocument: graph document types state their window; windows are checked") {
    assertRejected(
      semanticDocument.copy(window = None),
      "window: required for researcher_profile documents"
    )
    assertRejected(
      semanticDocument.copy(window = Some(YearWindow(2025, 2015))),
      "window: startYear 2025 exceeds endYear 2015"
    )
  }

  test("SemanticDocument: blank text and missing provenance are rejected") {
    val blank = semanticDocument.copy(
      text = " ",
      provenance = semanticDocument.provenance.copy(contentHash = Some(ContentHash.of(" ")))
    )
    assertRejected(blank, "text: must not be blank")
    assertRejected(semanticDocument.copy(provenance = null), "provenance: missing (null)")
  }

  // ---- VectorMetadata --------------------------------------------------------------------

  test("VectorMetadata: no field may be longer than 1024 characters") {
    val long = "T" + "1" * 1024
    assertRejected(
      vectorMetadata.copy(topicId = Some(long)),
      "topicId: 1025 characters exceeds the 1024-character limit"
    )
    assertEquals(errorsOf(vectorMetadata.copy(topicId = Some("T" + "1" * 1023))), Nil)
    assertRejected(
      vectorMetadata.copy(embeddingModel = "m" * 1025),
      "embeddingModel: 1025 characters exceeds"
    )
    assertRejected(
      vectorMetadata.copy(textS3Uri = "s3://example-bucket/" + "k" * 1010),
      "textS3Uri: 1030 characters exceeds"
    )
    val p = vectorMetadata.provenance
    assertRejected(
      vectorMetadata.copy(provenance = p.copy(sourceRecordIds = List("r" * 1025))),
      "provenance.sourceRecordIds[0]: 1025 characters exceeds"
    )
    assertRejected(
      vectorMetadata.copy(provenance = p.copy(jobName = "j" * 2000)),
      "provenance.jobName: 2000 characters exceeds"
    )
    decodeError[VectorMetadata](
      mutate(metadataJson)(_("venueKey") = ujson.Str("conf/" + "x" * 1100)),
      "venueKey: 1105 characters exceeds"
    )
  }

  test("VectorMetadata: long document text cannot hide in metadata") {
    val text = "word " * 400
    assert(text.length > 1024)
    assertRejected(
      vectorMetadata.copy(topicId = Some(text.replace(" ", "_"))),
      "characters exceeds"
    )
    // The record has no field that carries document text.
    val keys = ujson.read(metadataJson).obj.keys.toSet
    List("text", "snippet", "body", "content", "documentText").foreach(k => assert(!keys(k), k))
  }

  test("VectorMetadata: vectorKey and indexName follow the SemanticDocument rules") {
    assertRejected(vectorMetadata.copy(vectorKey = "k" * 1025), "vectorKey: 1025 UTF-8 bytes")
    assertRejected(vectorMetadata.copy(vectorKey = ""), "vectorKey: must not be blank")
    assertRejected(vectorMetadata.copy(indexName = "Bad_Index"), "indexName: 'Bad_Index'")
    decodeError[VectorMetadata](
      mutate(metadataJson)(_("indexName") = ujson.Str("x")),
      "indexName: 'x' must match"
    )
  }

  test("VectorMetadata: textHash is well-formed and equals provenance.contentHash") {
    val other = ContentHash.of("other text")
    assertRejected(vectorMetadata.copy(textHash = other), "provenance.contentHash: must equal")
    assertRejected(
      vectorMetadata.copy(provenance = vectorMetadata.provenance.copy(contentHash = None)),
      "provenance.contentHash: must equal textHash"
    )
    assertRejected(vectorMetadata.copy(textHash = "sha256:abc"), "textHash: 'sha256:abc'")
    decodeError[VectorMetadata](
      mutate(metadataJson)(_("textHash") = ujson.Str(other)),
      "provenance.contentHash: must equal textHash"
    )
  }

  test("VectorMetadata: subject fields per document type") {
    assertRejected(
      vectorMetadata.copy(researcherId = None),
      "researcherId: required for researcher_profile vectors"
    )
    val community = vectorMetadata.copy(
      documentType = SemanticDocumentType.CommunitySummary,
      researcherId = None
    )
    assertRejected(community, "communityId: required for community_summary vectors")
    assertEquals(errorsOf(community.copy(communityId = Some(sousa))), Nil)
    val path = vectorMetadata.copy(
      documentType = SemanticDocumentType.CollaborationPath,
      researcherId = None
    )
    assertRejected(path, "startResearcherId: required")
    assertRejected(path, "endResearcherId: required")
    assertRejected(path, "pathLength: required")
    val fullPath =
      path.copy(
        startResearcherId = Some(sousa),
        endResearcherId = Some(campos),
        pathLength = Some(1)
      )
    assertEquals(errorsOf(fullPath), Nil)
    assertRejected(fullPath.copy(endResearcherId = Some(sousa)), "two different endpoints")
    assertRejected(fullPath.copy(pathLength = Some(0)), "pathLength: 0 must be >= 1")
    val paper = vectorMetadata.copy(
      documentType = SemanticDocumentType.PaperSummary,
      researcherId = None,
      windowStartYear = None,
      windowEndYear = None
    )
    assertRejected(paper, "workId: required for paper_summary vectors")
    assertEquals(
      errorsOf(paper.copy(workId = Some(workId), year = Some(2023), venueKey = Some("conf/sigir"))),
      Nil
    )
    assertRejected(paper.copy(workId = Some(workId), year = Some(0)), "year: year 0")
    assertRejected(paper.copy(workId = Some(workId), venueKey = Some("sigir")), "venueKey: 'sigir'")
    val topic = paper.copy(documentType = SemanticDocumentType.TopicInstitutionSummary)
    assertRejected(topic, "topicId/institutionId: one is required")
    assertEquals(errorsOf(topic.copy(institutionId = Some("I43553435"))), Nil)
    assertRejected(topic.copy(institutionId = Some("institution 1")), "institutionId")
    assertRejected(topic.copy(topicId = Some("T 1")), "topicId: 'T 1'")
  }

  test("VectorMetadata: malformed IDs are rejected on decode") {
    List(
      "documentId" -> "malformed chunk ID",
      "researcherId" -> "malformed researcher ID",
      "runId" -> "malformed run ID",
      "sourceEvidenceId" -> "malformed evidence ID"
    ).foreach { case (field, message) =>
      decodeError[VectorMetadata](mutate(metadataJson)(_(field) = ujson.Str("x:v1:00")), message)
    }
    decodeError[VectorMetadata](
      mutate(metadataJson)(_("workId") = ujson.Str(sousa.value)),
      "malformed work ID"
    )
  }

  test("VectorMetadata: window years are paired, ordered, and required for graph types") {
    assertRejected(vectorMetadata.copy(windowEndYear = None), "must be both present or both absent")
    assertRejected(
      vectorMetadata.copy(windowStartYear = None, windowEndYear = None),
      "windowStartYear/windowEndYear: required for researcher_profile vectors"
    )
    assertRejected(
      vectorMetadata.copy(windowStartYear = Some(2026)),
      "startYear 2026 exceeds endYear 2025"
    )
  }

  test("VectorMetadata: source, URI, model, dimensions and run checks") {
    assertRejected(vectorMetadata.copy(sourceDataset = "DBLP"), "sourceDataset: 'DBLP'")
    List("https://example.invalid/x", "s3://example-bucket", "s3://Bad_Bucket/x", "s3://b/x")
      .foreach(u => assertRejected(vectorMetadata.copy(textS3Uri = u), "textS3Uri: '"))
    assertRejected(vectorMetadata.copy(embeddingModel = "voyage 4"), "embeddingModel: 'voyage 4'")
    assertRejected(vectorMetadata.copy(embeddingDimensions = 0), "embeddingDimensions: 0")
    assertRejected(vectorMetadata.copy(embeddingDimensions = 4097), "embeddingDimensions: 4097")
    assertEquals(errorsOf(vectorMetadata.copy(embeddingDimensions = 4096)), Nil)
    assertRejected(vectorMetadata.copy(runId = graphRun), "runId: ")
  }

  test("VectorMetadata: absent Option fields are rejected (written as null, never omitted)") {
    val result = Contract.decode[VectorMetadata](mutate(metadataJson)(_.obj.remove("workId")))
    assert(result.isLeft, result)
    assertEquals(ujson.read(metadataJson)("workId"), ujson.Null)
  }

  test("VectorMetadata carries the embedding model and a text pointer, in a frozen field order") {
    assertEquals(
      ujson.read(metadataJson).obj.keys.toList,
      List(
        "schemaVersion",
        "vectorKey",
        "indexName",
        "documentId",
        "documentType",
        "researcherId",
        "communityId",
        "startResearcherId",
        "endResearcherId",
        "workId",
        "topicId",
        "institutionId",
        "windowStartYear",
        "windowEndYear",
        "year",
        "venueKey",
        "pathLength",
        "sourceDataset",
        "textS3Uri",
        "textHash",
        "sourceEvidenceId",
        "embeddingModel",
        "embeddingDimensions",
        "runId",
        "provenance"
      )
    )
    assertEquals(
      ujson.read(documentJson).obj.keys.toList,
      List(
        "schemaVersion",
        "documentId",
        "documentType",
        "indexName",
        "vectorKey",
        "subjectIds",
        "window",
        "text",
        "sourceEvidenceId",
        "provenance"
      )
    )
  }
}
