package org.scholarchain.storage

import scala.collection.immutable.ListMap

import org.scholarchain.core._
import org.scholarchain.storage.ports.{MetaValue, StoredMetadata}
import org.scholarchain.storage.vectors._

class VectorMetadataSuite extends munit.FunSuite {
  import TestFixtures._

  private val spec = IndexSpecCatalog.load()
  private val profileSpec = spec.forType(SemanticDocumentType.ResearcherProfile)
  private val text = profileText(rA)
  private val hash = ContentHash.of(text)
  private val ingestRun = StorageVersion.runId("storage.vector-ingest", "sha256:x", "sha256:y")

  private def meta(): VectorMetadata = VectorMetadata(
    schemaVersion = 1,
    vectorKey = s"rp:$Full:${rA.value}",
    indexName = "researcher-profile-v1",
    documentId = ChunkId
      .forSubjects(SemanticDocumentType.ResearcherProfile, List(rA.value), Some(Full), 0, hash)
      .toOption
      .get,
    documentType = SemanticDocumentType.ResearcherProfile,
    researcherId = Some(rA),
    communityId = None,
    startResearcherId = None,
    endResearcherId = None,
    workId = None,
    topicId = None,
    institutionId = None,
    windowStartYear = Some(2015),
    windowEndYear = Some(2025),
    year = None,
    venueKey = None,
    pathLength = None,
    sourceDataset = "dblp",
    textS3Uri =
      s"s3://example-artifact-bucket/semantic-context/text/v1/index=researcher-profile-v1/sha256=${hash.drop(7)}.txt",
    textHash = hash,
    sourceEvidenceId = Some(profileSummaries.head.evidenceId),
    embeddingModel = "storage-stub-sha256-v1",
    embeddingDimensions = 1024,
    runId = ingestRun,
    provenance = Provenance(
      1,
      SourceType.Pipeline,
      "scholarchain-graph",
      "dblp-2026-11-01",
      List(rA.value),
      ingestRun,
      "storage.vector-ingest",
      "graph-v1",
      Some(hash)
    )
  )

  test("the index spec file is valid: five indexes, cosine, 1024 dimensions, key limits") {
    assertEquals(spec.validate, Nil)
    assertEquals(
      spec.indexes.map(_.name),
      List(
        "researcher-profile-v1",
        "collaboration-community-v1",
        "paper-abstract-v1",
        "collaboration-path-v1",
        "topic-institution-summary-v1"
      )
    )
    assertEquals((spec.dimension, spec.distanceMetric, spec.dataType), (1024, "cosine", "float32"))
    spec.indexes.foreach { i =>
      assert(i.nonFilterableKeys.size <= S3VectorsLimits.MaxNonFilterableKeys)
      assert(i.allKeys.size <= S3VectorsLimits.MaxMetadataKeys)
      List("textS3Uri", "textHash", "documentId", "sourceEvidenceId", "runId").foreach(k =>
        assert(i.nonFilterableKeys.contains(k), k)
      )
      List(
        "documentType",
        "researcherId",
        "communityId",
        "windowStartYear",
        "windowEndYear",
        "sourceDataset",
        "topicId",
        "institutionId",
        "embeddingModel"
      )
        .foreach(k => assert(i.filterableKeys.contains(k), k))
    }
    assertEquals(spec.checkConfig(storageConfig.vectors, 1024), Nil)
    assert(spec.checkConfig(storageConfig.vectors, 512).nonEmpty)
    assert(
      spec
        .checkConfig(
          storageConfig.vectors.copy(researcherProfileIndex = "researcher-profile-v2"),
          1024
        )
        .nonEmpty
    )
  }

  test("an index spec with 11 non-filterable keys is rejected") {
    val extra = (1 to 11).map(i => s"k$i").toList
    assert(
      spec
        .copy(indexes = spec.indexes.map(i => i.copy(nonFilterableKeys = extra)))
        .validate
        .nonEmpty
    )
  }

  test("encode and decode round-trip; the embedding model is stored; no document text is stored") {
    val m = meta()
    val stored = VectorMetadataCodec.encode(m, profileSpec).toOption.get
    assertEquals(VectorMetadataCodec.decode(m.vectorKey, m.indexName, stored), Right(m))
    assertEquals(
      stored.filterable.get("embeddingModel"),
      Some(MetaValue.Str("storage-stub-sha256-v1"))
    )
    assertEquals(stored.filterable.get("embeddingDimensions"), Some(MetaValue.Num(1024)))
    assertEquals(
      stored.nonFilterable.keySet,
      Set(
        "textS3Uri",
        "textHash",
        "documentId",
        "sourceEvidenceId",
        "runId",
        "sourceRecordIds",
        "schemaVersion"
      )
    )
    assertEquals(VectorMetadataCodec.checkLimits(stored, profileSpec, Some(text)), Nil)
    val sizes = VectorMetadataCodec.sizes(stored)
    assert(
      sizes.filterableBytes <= 2048 && sizes.totalBytes <= 40960 && sizes.keys <= 50 && sizes.nonFilterableKeys <= 10,
      sizes
    )
  }

  test("missing and malformed metadata are rejected on decode") {
    val stored = VectorMetadataCodec.encode(meta(), profileSpec).toOption.get
    val noHash = stored.copy(nonFilterable = stored.nonFilterable.removed("textHash"))
    assert(
      VectorMetadataCodec
        .decode("k", "researcher-profile-v1", noHash)
        .left
        .exists(_.exists(_.contains("textHash")))
    )
    val wrongType = stored.copy(filterable =
      stored.filterable.updated("embeddingDimensions", MetaValue.Str("1024"))
    )
    assert(VectorMetadataCodec.decode("k", "researcher-profile-v1", wrongType).isLeft)
    val badId = stored.copy(filterable =
      stored.filterable.updated("researcherId", MetaValue.Str("researcher:v1:nothex"))
    )
    assert(VectorMetadataCodec.decode("k", "researcher-profile-v1", badId).isLeft)
    val unknown =
      stored.copy(filterable = stored.filterable.updated("surprise", MetaValue.Str("x")))
    assert(VectorMetadataCodec.decode("k", "researcher-profile-v1", unknown).isLeft)
    val noProfileSubject = stored.copy(filterable = stored.filterable.removed("researcherId"))
    assert(
      VectorMetadataCodec.decode(meta().vectorKey, "researcher-profile-v1", noProfileSubject).isLeft
    )
  }

  test(
    "metadata limits: filterable > 2 KB, total > 40 KB, > 50 keys and > 10 non-filterable keys are rejected"
  ) {
    val stored = VectorMetadataCodec.encode(meta(), profileSpec).toOption.get
    val bigFilterable =
      stored.copy(filterable = stored.filterable.updated("venueKey", MetaValue.Str("v" * 2100)))
    assert(
      VectorMetadataCodec
        .checkLimits(bigFilterable, profileSpec, None)
        .exists(_.contains("filterable metadata"))
    )
    val bigTotal = stored.copy(nonFilterable =
      stored.nonFilterable.updated("sourceRecordIds", MetaValue.StrList(List.fill(50)("x" * 1000)))
    )
    assert(VectorMetadataCodec.checkLimits(bigTotal, profileSpec, None).exists(_.contains("40960")))
    val many = stored.copy(filterable =
      stored.filterable ++ ListMap.from((1 to 40).map(i => s"k$i" -> MetaValue.Num(i.toLong)))
    )
    assert(
      VectorMetadataCodec.checkLimits(many, profileSpec, None).exists(_.contains("keys exceed 50"))
    )
    val nf = stored.copy(nonFilterable =
      stored.nonFilterable ++ ListMap.from((1 to 4).map(i => s"n$i" -> MetaValue.Num(1L)))
    )
    assert(
      VectorMetadataCodec
        .checkLimits(nf, profileSpec, None)
        .exists(_.contains("non-filterable keys exceed 10"))
    )
    // a core field over 1024 characters is rejected by encode
    assert(VectorMetadataCodec.encode(meta().copy(sourceDataset = "d" * 1025), profileSpec).isLeft)
  }

  test("document text in metadata is detected and rejected") {
    val stored = VectorMetadataCodec.encode(meta(), profileSpec).toOption.get
    val withText =
      stored.copy(filterable = stored.filterable.updated("venueKey", MetaValue.Str(text)))
    assert(
      VectorMetadataCodec
        .checkLimits(withText, profileSpec, Some(text))
        .contains("metadata contains document text")
    )
    assert(
      !VectorMetadataCodec.AllKeys.exists(k =>
        k.toLowerCase.contains("text") && k != "textS3Uri" && k != "textHash"
      )
    )
  }

  test("metadata for the wrong index or type is rejected") {
    assert(
      VectorMetadataCodec.encode(meta(), spec.forType(SemanticDocumentType.CommunitySummary)).isLeft
    )
  }

  test("filterable and non-filterable keys are disjoint and cover every stored key") {
    assert(
      VectorMetadataCodec.FilterableKeys.intersect(VectorMetadataCodec.NonFilterableKeys).isEmpty
    )
    val s = StoredMetadata(ListMap.empty, ListMap.empty)
    assertEquals(VectorMetadataCodec.sizes(s).keys, 0)
  }

  test(
    "vector metadata carries no graph metric, count, component, community size or bridge score"
  ) {
    val numeric = VectorMetadataCodec
      .encode(meta(), profileSpec)
      .toOption
      .get
      .all
      .collect { case (k, MetaValue.Num(_)) => k }
      .toSet
    assertEquals(
      numeric,
      Set("windowStartYear", "windowEndYear", "embeddingDimensions", "schemaVersion")
    )
    val graphFacts = CollaborationMetric.values.map(_.wire).toSet ++
      Set(
        "P",
        "C",
        "T",
        "A",
        "M",
        "W",
        "weight",
        "jointPublicationCount",
        "coreness",
        "componentSize"
      )
    assert(VectorMetadataCodec.AllKeys.intersect(graphFacts).isEmpty)
    // the only other numbers a type can carry are descriptive filters: year, pathLength
    assertEquals(
      VectorMetadataCodec.AllKeys.filter(k =>
        k.endsWith("Year") || k == "year" || k == "pathLength"
      ),
      Set("windowStartYear", "windowEndYear", "year", "pathLength")
    )
  }
}
