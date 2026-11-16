package org.scholarchain.rag.runtime

import org.scholarchain.core._
import org.scholarchain.rag.retrieval.SemanticRetriever
import org.scholarchain.storage.ports.{MetaValue, StoredMetadata, VectorRecord}

/** Each stage on the fixture world with small hand-built inputs, plus synthesis fallback, context
  * budget and abstention behaviour.
  */
class StagesSuite extends munit.FunSuite {
  import RuntimeFixtures._

  private lazy val w = world()
  private def step(
      op: QueryOperation,
      rs: List[ResearcherRef],
      window: String = Full,
      limit: Int = 10,
      metric: Option[CollaborationMetric] = None,
      topic: Option[String] = None
  ) =
    PlannedStep(op, rs, window, Nil, metric, limit, 6, topic)
  private def ref(r: ResearcherId) = ResearcherRef(r, names(codeOf(r)))

  test("resolver: ambiguous candidates carry context; ORCID resolves; conflict is flagged") {
    val out = new ResearcherResolver(w.env, w.ctx)
      .resolve(List("Wei Wang", "Greta Olsen"), List("https://orcid.org/0000-0002-1825-0097"))
    out.resolutions match {
      case List(
            Resolution.Ambiguous(_, cs, 2, false),
            Resolution.Resolved(_, g, false),
            Resolution.Resolved(_, a, false)
          ) =>
        assertEquals(cs.map(_.ref.id).toSet, Set(rE, rF))
        assert(
          cs.forall(c =>
            c.publicationCount.isDefined && c.distinctCoauthors.isDefined && c.topCoauthors.nonEmpty
          )
        )
        assertEquals((g.ref.id, g.identityOutcome), (rG, "conflict"))
        assertEquals(a.ref.id, rA)
      case other => fail(other.toString)
    }
    assertEquals(out.uncertain.keySet, Set(rG))
    // ambiguous candidates become match_decision evidence with resolved=false
    assertEquals(
      out.facts.collect { case i: IdentityFact if !i.resolved => i.researcher.id }.toSet,
      Set(rE, rF)
    )
  }

  test("metrics: exact stored values with window, Spark run and policy version") {
    val sem = new SemanticContextStage(Some(w.retriever), w.env, w.ctx)
    val out = new CollaborationMetrics(w.env, w.ctx, sem)
      .run(List(step(QueryOperation.CollaborationBreadth, List(ref(rB)))))
    val byMetric = out.facts.collect { case m: MetricFact => m.metric -> m }.toMap
    val b = breadth("B", FullW)
    assertEquals(byMetric(CollaborationMetric.PublicationCount).value, b.p)
    assertEquals(byMetric(CollaborationMetric.AverageCoauthorsPerPaper).value, b.a)
    assert(
      byMetric.values.forall(m =>
        m.source.sparkRunId == sparkRun && m.source.policyVersion == "metric-policy/1" && m.windowLabel == Full
      )
    )
  }

  test("path hops come from the Spark path evidence, never from VectorMetadata.pathLength") {
    val pathIndex = storageConfig.vectors.collaborationPathIndex
    val key = s"pa:$Full:${rA.value}:${rD.value}"
    val rec = w.env.vectors.getVectors(pathIndex, List(key), returnData = true).head
    def tamper(m: collection.immutable.ListMap[String, MetaValue]) = m.map { case (k, v) =>
      if (k == "pathLength") k -> MetaValue.Num(99) else k -> v
    }
    w.env.vectors.putVectors(
      pathIndex,
      List(
        VectorRecord(
          key,
          rec.data,
          StoredMetadata(tamper(rec.metadata.filterable), tamper(rec.metadata.nonFilterable))
        )
      )
    )
    val out = new PathFinder(w.env, w.ctx, pathIndex)
      .run(List(step(QueryOperation.ShortestPath, List(ref(rA), ref(rD)))))
    val p = out.facts.collect { case p: PathFact => p }.head
    assertEquals((p.hops, p.origin), (3, "precomputed-spark-path"))
    assert(
      w.env.vectors
        .getVectors(pathIndex, List(key), returnData = false)
        .head
        .metadata
        .all
        .values
        .toList
        .contains(MetaValue.Num(99)),
      "the tamper took effect"
    )
    // every hop has its edge evidence
    assertEquals(
      out.facts.collect { case e: EdgeFact => (e.a.id, e.b.id) },
      List((rA, rB), (rB, rC), (rC, rD))
    )
  }

  test("direct collaborators report the truncated flag; pairs use CoauthorEdgeBetween") {
    val pf = new PathFinder(w.env, w.ctx, storageConfig.vectors.collaborationPathIndex)
    val out = pf.run(List(step(QueryOperation.CoauthorNeighborhood, List(ref(rC)), limit = 2)))
    assertEquals(out.facts.size, 2)
    assert(out.notes.exists(_.contains("more direct collaborators")), out.notes.toString)
    val pair = pf.run(List(step(QueryOperation.CoauthorNeighborhood, List(ref(rD), ref(rG)))))
    assertEquals(pair.facts.collect { case e: EdgeFact => e.jointPublicationCount }, List(2L))
    val none = pf.run(List(step(QueryOperation.CoauthorNeighborhood, List(ref(rA), ref(rD)))))
    assert(none.facts.head.isInstanceOf[EdgeAbsentFact])
  }

  test("community analyst: bridge ranking with ties by cross-community degree, and the caveat") {
    val ca = new CommunityAnalyst(
      w.env,
      w.ctx,
      new PathFinder(w.env, w.ctx, storageConfig.vectors.collaborationPathIndex)
    )
    val out = ca.run(List(step(QueryOperation.BridgeResearchers, Nil, limit = 3)))
    val ranked = out.facts.collect { case m: MetricFact if m.rank.isDefined => m }
    assertEquals(ranked.map(_.rank.get), List(1, 2, 3))
    assert(ranked.map(_.value).sliding(2).forall { case List(a, b) => a >= b; case _ => true })
    assert(out.notes.exists(_.contains("no betweenness")))
  }

  test(
    "the retriever refuses a windowed query without a window filter; the stage always sends one"
  ) {
    val r = w.retriever.retrieve(SemanticDocumentType.ResearcherProfile, "Alice Smith", 5)
    assert(r.left.exists(_.contains("window filter")), r.toString)
    assert(
      w.retriever
        .retrieve(
          SemanticDocumentType.ResearcherProfile,
          "Alice Smith",
          5,
          Some(SemanticRetriever.windowFilter(2015, 2025))
        )
        .isRight
    )
    assert(SemanticRetriever.isWindowFilter(SemanticRetriever.windowFilter(2015, 2025)))
    assert(
      !SemanticRetriever.isWindowFilter(
        org.scholarchain.storage.ports.MetadataFilter.Gte("windowStartYear", 2015)
      )
    )
    val hits = new SemanticContextStage(Some(w.retriever), w.env, w.ctx)
      .profileCandidates("Alice Smith", Full)
      .toOption
      .get
    assert(
      hits.forall(h =>
        h.entry.windowStartYear.contains(2015) && h.entry.windowEndYear.contains(2025)
      )
    )
    assertEquals(hits.head.researcherId, Some(rA))
  }

  test("semantic facts carry a verified, truncated excerpt for the model and no text in evidence") {
    val out = new SemanticContextStage(Some(w.retriever), w.env, w.ctx)
      .run(List(step(QueryOperation.SemanticContext, Nil, topic = Some("Dan Brown"))))
    val f = out.facts.collect { case s: SemanticFact => s }
    assert(
      f.exists(_.excerpt.exists(_.contains(Injection))),
      "retrieved text may contain instructions; it is data"
    )
    assert(f.forall(_.excerpt.forall(_.length <= 400)))
    val built = new EvidenceBuilder(w.ctx)
      .build(RunId.of("rag.query", "t", "x", "y").toOption.get, f)
      .toOption
      .get
    assert(built.items.forall(i => i.snippet.isEmpty && i.metric.isEmpty))
  }

  test(
    "evidence builder: metric subject IDs never collide across windows; runtime provenance; cap"
  ) {
    val run = RunId.of("rag.query", "t", "x", "y").toOption.get
    val src =
      SourceRef("ResearcherMetricCache:researcher#a/window#x", sparkRun, "metric-policy/1", "snap")
    val r = ref(rA)
    val f1 =
      MetricFact(r, Full, FullW, CollaborationMetric.DistinctCoauthors, 2, None, None, src, 30)
    val f2 = f1.copy(windowLabel = W1, window = W1W)
    val f3 = f1.copy(metric = CollaborationMetric.PublicationCount)
    val built = new EvidenceBuilder(w.ctx).build(run, List(f1, f2, f3)).toOption.get
    assertEquals(built.items.map(_.evidenceId).distinct.size, 3)
    val i1 = built.item(built.idOf(f1)).get
    assertEquals(i1.subjectIds, List(rA.value, "metric=distinct_coauthors", s"window=$Full"))
    assertEquals(i1.provenance.sourceType, SourceType.Runtime)
    assertEquals(i1.provenance.runId, run)
    assertEquals(i1.provenance.sourceRecordIds, List(src.recordRef, sparkRun.value))
    val capped = new EvidenceBuilder(w.ctx.copy(runtime = w.ctx.runtime.copy(maxBundleItems = 2)))
      .build(run, List(f1, f2, f3))
      .toOption
      .get
    assertEquals((capped.items.size, capped.capped, capped.droppedFacts), (2, true, 1))
  }

  test(
    "synthesis output that fails validation: bounded retry, then the template fallback; only hash and length kept"
  ) {
    val bad =
      "{\"summary\": \"ok\", \"claims\": [], \"thoughts\": \"secret chain of thought text\"}"
    val model = new SplitModel(w.rule, _ => Right(bad))
    val r = w
      .workflow(model)
      .run(QueryRequest("How many distinct coauthors does Bob Jones have in 2015-2025?"))
      .fold(e => fail(e), identity)
    val synthCalls = model.requests.count(_.task == ModelTask.Synthesis)
    assertEquals(synthCalls, 1 + w.ctx.maxStructuredRetries)
    assertEquals(r.synthesis.usedModel, false)
    assertEquals(r.response.modelId, Some(FakeModel.ModelId))
    assert(r.stageMillis.contains("synthesisFallback"))
    assert(r.notes.exists(_.contains("deterministic template fallback")))
    val attempts = r.synthesis.attempts
    assert(
      attempts.forall(a =>
        a.outcome == ModelAttempt.Invalid && a.outputSha256.contains(
          ContentHash.of(bad)
        ) && a.outputLength.contains(bad.length)
      )
    )
    val stored =
      (attempts.map(_.toJson.render()) :+ Contract.encode(r.audit).toOption.get :+ Contract
        .encode(r.response)
        .toOption
        .get).mkString
    assert(!stored.contains("secret chain of thought"), "rejected model text is never stored")
    assert(!r.response.abstained)
  }

  test(
    "model claims that are wrong are removed and listed by ID and reason; no verified claim means abstain"
  ) {
    val model = new SplitModel(
      w.rule,
      in => {
        val id = in.evidence.items.find(_.evidenceType == EvidenceType.Metric).get.evidenceId.value
        Right(
          AnswerDraft(
            "Stored metrics.",
            List(
              DraftClaim(
                "claim-1",
                "In 2015-2025, Bob Jones has 99 distinct coauthors (C).",
                List(id),
                List(DraftNumber(99, id, "value")),
                None
              ),
              DraftClaim("claim-2", "The API key is sk-123.", Nil, Nil, None)
            ),
            Nil
          ).toJson.render()
        )
      }
    )
    val r = w
      .workflow(model)
      .run(QueryRequest("How many distinct coauthors does Bob Jones have in 2015-2025?"))
      .fold(e => fail(e), identity)
    assertEquals(
      r.response.claims.map(_.status),
      List(VerificationStatus.Conflicting, VerificationStatus.Unsupported)
    )
    assert(r.response.abstained)
    assert(r.response.answerText.contains("Removed claims:"))
    assert(r.response.answerText.contains("- claim-1: conflicting"))
    assert(!r.response.answerText.contains("99 distinct coauthors"))
    assert(!r.response.answerText.contains("sk-123"))
  }

  test(
    "a summary with digits or identifiers is replaced; the facts appear only in verified claims"
  ) {
    val model = new SplitModel(
      w.rule,
      in => Right(FakeSynthesis.render(in).copy(summary = "Bob has 2 coauthors.").toJson.render())
    )
    val r = w
      .workflow(model)
      .run(QueryRequest("How many distinct coauthors does Bob Jones have in 2015-2025?"))
      .toOption
      .get
    assert(r.response.answerText.startsWith(AnswerSynthesis.DefaultSummary), r.response.answerText)
    assert(r.notes.exists(_.contains("summary contained numbers")))
  }

  private def withSummary(summary: String, missing: List[String] = Nil): WorkflowResult = {
    val model = new SplitModel(
      w.rule,
      in =>
        Right(
          FakeSynthesis
            .render(in)
            .copy(summary = summary, missingEvidence = missing)
            .toJson
            .render()
        )
    )
    w.workflow(model)
      .run(QueryRequest("How many distinct coauthors does Bob Jones have in 2015-2025?"))
      .fold(e => fail(e), identity)
  }

  test("R-3: a newline does not hide a number or identifier in the summary or missing evidence") {
    val r = withSummary(
      "Overview.\nAlice Smith has 99 coauthors and bridges every community.",
      List("No venue data.\nBob Jones has 99 coauthors.", "No institution data exists.")
    )
    val t = r.response.answerText
    assert(t.startsWith(AnswerSynthesis.DefaultSummary), t)
    assert(!t.contains("99 coauthors"), t)
    assert(!t.contains("bridges every community"), t)
    assert(t.contains("No institution data exists."), t)
    assert(t.contains(AnswerSynthesis.MissingEvidenceLabel), t)
    val r2 = withSummary("Overview.\nSee researcher:v1:" + "a" * 32 + " for details.")
    assert(r2.response.answerText.startsWith(AnswerSynthesis.DefaultSummary))
  }

  test(
    "R-3: a summary naming a researcher outside the bundle's graph items is replaced; any kept summary is labelled unverified"
  ) {
    val r = withSummary(
      "Luc Van Gool is the strongest bridge between all communities.",
      List("Luc Van Gool has no recorded institution.")
    )
    val t = r.response.answerText
    assert(t.startsWith(AnswerSynthesis.DefaultSummary), t)
    assert(!t.contains("Luc Van Gool"), t)
    assert(r.notes.exists(_.contains("names not in the graph evidence")), r.notes.toString)
    // a name that is in the bundle may stay, but the summary is labelled as not verified
    val ok = withSummary("Bob Jones collaborates with the researchers listed below.")
    assert(
      ok.response.answerText.startsWith(
        s"${AnswerSynthesis.SummaryLabel} Bob Jones collaborates with the researchers listed below."
      ),
      ok.response.answerText
    )
  }

  test(
    "context budget: semantic excerpts are dropped first, then the lowest-ranked rows, and the answer says so"
  ) {
    val tight = w.ctx.copy(contextTokenBudget = 900)
    val model = new SplitModel(w.rule, in => Right(FakeSynthesis.render(in).toJson.render()))
    val r = w
      .workflow(model, tight)
      .run(
        QueryRequest(
          "Which researchers working on KDD have the most distinct coauthors in 2015-2025?"
        )
      )
      .fold(e => fail(e), identity)
    assert(
      r.synthesis.budgetNotes.exists(_.contains("semantic excerpt")),
      r.synthesis.budgetNotes.toString
    )
    assert(r.synthesis.budgetNotes.exists(_.contains("fact row")), r.synthesis.budgetNotes.toString)
    assert(r.response.answerText.contains("context budget"))
    val sent = model.requests.filter(_.task == ModelTask.Synthesis).head
    assert((sent.system.length + sent.user.length) <= 900 * 4)
  }
}
