package org.scholarchain.storage

import org.scholarchain.core._
import org.scholarchain.storage.vectors.VectorKeys

class VectorKeysSuite extends munit.FunSuite {
  import TestFixtures._
  import SemanticDocumentType._

  test("keys follow the subject rule for every document type") {
    assertEquals(
      VectorKeys.derive(ResearcherProfile, Some(Full), List(rA.value), 0),
      Right(s"rp:$Full:${rA.value}")
    )
    assertEquals(
      VectorKeys
        .derive(CommunitySummary, Some("w2015-2017"), List(rB.value, rA.value, rB.value), 0),
      Right(s"cm:w2015-2017:${rB.value}")
    )
    assertEquals(
      VectorKeys.derive(CollaborationPath, Some(Full), pathSubjects, 0),
      Right(s"pa:$Full:${rA.value}:${rD.value}")
    )
    assertEquals(
      VectorKeys.derive(PaperSummary, None, List(w1.value, rA.value), 0),
      Right(s"pp:${w1.value}")
    )
    assertEquals(
      VectorKeys.derive(TopicInstitutionSummary, None, List("T10181"), 0),
      Right("ti:-:T10181")
    )
    assertEquals(
      VectorKeys.derive(ResearcherProfile, Some(Full), List(rA.value), 2),
      Right(s"rp:$Full:${rA.value}#2")
    )
  }

  test("keys never depend on the run or the text") {
    val e1 = profileSummaries.head
    val other = RunId.of("spark.test", "code", "other-input", "config").toOption.get
    val d1 =
      SummaryDocuments.build("researcher_profile", Full, e1, storageConfig.vectors).toOption.get
    val e2 =
      summary(EvidenceType.Metric, e1.subjectIds, "Different text.", e1.metric).copy(provenance =
        prov(e1.subjectIds, Some(ContentHash.of("Different text."))).copy(runId = other)
      )
    val e2b = e2.copy(evidenceId = EvidenceId.of(EvidenceType.Metric, other, e2.subjectIds))
    val d2 =
      SummaryDocuments.build("researcher_profile", Full, e2b, storageConfig.vectors).toOption.get
    assertEquals(d1.vectorKey, d2.vectorKey)
    assertNotEquals(d1.documentId, d2.documentId)
  }

  test("malformed subjects and a document whose key breaks the rule are rejected") {
    assert(VectorKeys.derive(ResearcherProfile, Some(Full), List(rA.value, rB.value), 0).isLeft)
    assert(VectorKeys.derive(ResearcherProfile, None, List(rA.value), 0).isLeft)
    assert(VectorKeys.derive(CollaborationPath, Some(Full), List(rA.value), 0).isLeft)
    assert(VectorKeys.derive(PaperSummary, None, List(rA.value), 0).isLeft)
    assert(VectorKeys.derive(ResearcherProfile, Some(Full), List("not-an-id"), 0).isLeft)
    val d = SummaryDocuments
      .build("researcher_profile", Full, profileSummaries.head, storageConfig.vectors)
      .toOption
      .get
    assert(VectorKeys.check(d, Some(Full)).isRight)
    assert(VectorKeys.check(d.copy(vectorKey = d.vectorKey + "#0"), Some(Full)).isLeft)
    assert(VectorKeys.check(d.copy(vectorKey = d.vectorKey + "#3"), Some(Full)).isRight)
    assert(VectorKeys.check(d.copy(vectorKey = s"rp:$Full:${rB.value}"), Some(Full)).isLeft)
  }
}
