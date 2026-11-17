package org.scholarchain.contracts.crossrecord

import org.scholarchain.core.*
import org.scholarchain.rag.runtime.{
  QueryRequest,
  RuntimeFixtures,
  RuntimeServiceFixtures,
  WorkflowResult
}

/** Cross-record contract checks deferred from Phase 3: the IDs, windows and provenance that rag's
  * answer records, storage's stored records and the Spark export must agree on, over the fixture
  * world (a valid Spark JSON Lines export loaded through storage).
  */
class WindowProvenanceCrossRecordSuite extends munit.FunSuite {

  override val munitTimeout = scala.concurrent.duration.Duration(300, "s")

  private val Questions = List(
    RuntimeServiceFixtures.AnsweredQuestion,
    "Who are the direct collaborators of Carol King in 2015-2025?",
    "What is the shortest collaboration path between Alice Smith and Dan Brown in 2015-2025?",
    "Which researchers working on Dan Brown have the most distinct coauthors in 2015-2025?",
    "How many distinct coauthors does Greta Olsen have in 2021-2023?"
  )

  private lazy val results: List[WorkflowResult] = {
    val fx = RuntimeServiceFixtures.fixture()
    try
      Questions.map(q =>
        fx.service.workflow.run(QueryRequest(q)).fold(e => fail(s"$q: $e"), identity)
      )
    finally fx.service.close()
  }

  private val configured: Set[YearWindow] = RuntimeFixtures.windows.map(_._2).toSet

  test("every windowed graph evidence item carries a configured window or one work year") {
    val bad = results.flatMap { r =>
      r.response.bundle.toList.flatMap(_.items).collect {
        case i
            if i.evidenceType != EvidenceType.SemanticContext && i.window
              .exists(w => !configured.contains(w) && w.startYear != w.endYear) =>
          s"${r.request.question}: ${i.evidenceType.wire} ${i.evidenceId.value} ${i.window}"
      }
    }
    assertEquals(bad, Nil)
  }

  test("every claim window is the window of one of its cited items") {
    val bad = results.flatMap { r =>
      val byId = r.response.bundle.toList.flatMap(_.items).map(i => i.evidenceId -> i).toMap
      r.response.claims.collect {
        case c
            if c.window.nonEmpty && !c.evidenceIds
              .flatMap(byId.get)
              .flatMap(_.window)
              .contains(c.window.get) =>
          s"${r.request.question}: ${c.claimId} ${c.window}"
      }
    }
    assertEquals(bad, Nil)
  }

  test("cited IDs are in the bundle, and the audit lists the bundle's items and the graph run") {
    results.foreach { r =>
      val bundle = r.response.bundle.toList.flatMap(_.items).map(_.evidenceId).toSet
      val cited = r.response.claims.flatMap(_.evidenceIds).toSet
      assert(cited.subsetOf(bundle), s"${r.request.question}: cited outside the bundle")
      assert(cited.subsetOf(r.audit.evidenceIds.toSet), r.request.question)
      assertEquals(r.audit.bundleId, r.response.bundle.map(_.bundleId), r.request.question)
      assertEquals(r.audit.graphRunId, RuntimeFixtures.sparkRun, r.request.question)
      assertEquals(r.audit.queryRunId, r.queryRunId)
      assertEquals(r.audit.provenance.runId, r.queryRunId, r.request.question)
      assertEquals(r.response.plan.graphRunId, RuntimeFixtures.sparkRun)
    }
  }

  test("graph evidence keeps the Spark run's provenance; nothing claims the query run made it") {
    val bad = results.flatMap { r =>
      r.response.bundle.toList.flatMap(_.items).collect {
        case i
            if (i.evidenceType == EvidenceType.Metric || i.evidenceType == EvidenceType.GraphPath) &&
              i.provenance.sourceType == SourceType.Pipeline && i.provenance.runId == r.queryRunId =>
          s"${r.request.question}: ${i.evidenceId.value}"
      }
    }
    assertEquals(bad, Nil)
  }

  test("each stored bundle reads back equal to the response's bundle (EB3) and its items (EB1)") {
    val fx = RuntimeServiceFixtures.fixture()
    try {
      val r = fx.service.workflow
        .run(RuntimeServiceFixtures.answeredRequest)
        .fold(e => fail(e), identity)
      val b = r.response.bundle.getOrElse(fail("no bundle"))
      assertEquals(fx.service.stores.evidence.getBundle(b.bundleId), Right(Some(b)))
      b.items.foreach { i =>
        assertEquals(fx.service.stores.evidence.get(i.evidenceId, i.window), Right(Some(i)))
      }
    } finally fx.service.close()
  }
}
