package org.scholarchain.rag.runtime

import java.nio.file.{Files, Paths}
import java.util.concurrent.atomic.AtomicInteger

import scala.collection.mutable

import org.scholarchain.core._
import org.scholarchain.core.config.LlmProvider
import org.scholarchain.rag.RagFixtures
import org.scholarchain.rag.embedding.{BedrockInvoker, EmbeddingProviders, InputType}
import org.scholarchain.rag.ingest.RagIngestJob
import org.scholarchain.rag.retrieval.SemanticRetriever
import org.scholarchain.storage.StoreException
import org.scholarchain.storage.ports.{
  ConditionalResult,
  GraphQuery,
  GraphReadPort,
  GraphReadSettings,
  GraphResult,
  Item,
  ItemKey,
  KeyValueStore,
  MetadataFilter,
  VectorHit,
  VectorIndexPort,
  VectorRecord
}
import org.scholarchain.storage.runtime.RuntimeStores

/** Phase 11 runtime repairs on the fixture world (fake or scripted models, local stores): C-01,
  * C-02, C-03, C-05, U-3, U-5, U-10, D-01, the L-1 workflow path (v3 drops and counts, v2 rejects)
  * and the QueryAudit v2 population.
  */
class RuntimeRepairsSuite extends munit.FunSuite {
  import RuntimeFixtures._

  override val munitTimeout = scala.concurrent.duration.Duration(300, "s")

  private lazy val w = world()
  private lazy val root = Prompts.locate().fold(e => sys.error(e), identity)
  private lazy val promptsV2 = Prompts.loadSet(root, Prompts.V2).fold(e => sys.error(e), identity)
  private lazy val qEmb =
    EmbeddingProviders.create(config, InputType.Query).fold(e => sys.error(e), identity)

  private val Host = "db-prod-7.cluster-ro-q1w2e3.us-east-1.neptune.amazonaws.com"
  private val Arn = "arn:aws:dynamodb:us-east-1:123456789012:table/scholarchain-prod-run-ledger"
  private val Account = "123456789012"
  private val Bucket = "s3://scholarchain-prod-secret-bucket/"
  private val Sensitive = s"connect timed out to $Host reading $Arn for account $Account ($Bucket)"
  private val Secrets = List(Host, Arn, Account, Bucket, "scholarchain-prod")

  private val Collaborators = "Who are the direct collaborators of Carol King in 2015-2025?"
  private val Topic =
    "Which researchers working on KDD have the most distinct coauthors in 2015-2025?"
  private val Breadth = "How many distinct coauthors does Carol King have in 2015-2025?"
  private val Path =
    "What is the shortest collaboration path between Alice Smith and Dan Brown in 2015-2025?"

  private def shown(r: WorkflowResult): String = upickle.default.write(r.response)
  private def leaks(r: WorkflowResult): List[String] = Secrets.filter(shown(r).contains)

  /** The world's stores with a wrapped graph port or key-value store. */
  private def stores(
      graph: GraphReadPort => GraphReadPort = identity,
      kv: KeyValueStore => KeyValueStore = identity
  ): RuntimeStores =
    RuntimeStores
      .of(storageConfig, graph(w.env.graph), kv(w.env.storage.kv), w.env.vectors, w.env.textStore)
      .fold(e => sys.error(e), identity)

  private def workflow(
      s: RuntimeStores = w.env,
      model: LanguageModel = new FakeModel(w.rule),
      prompts: PromptSet = w.prompts,
      retriever: Option[SemanticRetriever] = Some(w.retriever)
  ): QueryWorkflow =
    new QueryWorkflow(
      s,
      w.ctx,
      model,
      prompts,
      retriever,
      storageConfig.vectors.collaborationPathIndex
    )

  private def run(wf: QueryWorkflow, q: String): WorkflowResult =
    wf.run(QueryRequest(q)).fold(e => fail(s"workflow Left: $e"), identity)

  private def graphFailing(
      pf: PartialFunction[GraphQuery, Boolean]
  ): GraphReadPort => GraphReadPort =
    base =>
      new GraphReadPort {
        def settings: GraphReadSettings = base.settings
        def run(q: GraphQuery): Either[String, GraphResult] =
          if (pf.applyOrElse(q, (_: GraphQuery) => false))
            Left(s"store failure (transient): $Sensitive")
          else base.run(q)
      }

  private def kvFailing(
      when: (String, Option[ItemKey]) => Boolean
  ): KeyValueStore => KeyValueStore =
    base =>
      new KeyValueStore {
        private def check(t: String, k: Option[ItemKey]): Unit =
          if (when(t, k)) throw new StoreException(Sensitive, true)
        def putIfAbsent(table: String, item: Item): ConditionalResult =
          base.putIfAbsent(table, item)
        def putIfVersion(table: String, item: Item, expectedVersion: Long): ConditionalResult =
          base.putIfVersion(table, item, expectedVersion)
        def get(table: String, key: ItemKey): Option[Item] = {
          check(table, Some(key)); base.get(table, key)
        }
        def query(
            table: String,
            pk: String,
            skPrefix: Option[String],
            limit: Int,
            descending: Boolean
        ): List[Item] = { check(table, None); base.query(table, pk, skPrefix, limit, descending) }
      }

  // ---- C-02: no downstream text in a 200 answer ------------------------------------------------

  test("C-02: graph Lefts holding store text never reach the answer; fixed causes are stated") {
    val g = graphFailing {
      case _: GraphQuery.CoauthorNeighbors => true
      case _: GraphQuery.PathBetween       => true
    }
    val r = run(workflow(stores(graph = g)), Collaborators)
    assertEquals(leaks(r), Nil, r.response.answerText)
    assert(r.response.answerText.contains(StageFailures.NeighborLookup), r.response.answerText)
    val p = run(
      workflow(stores(graph = g)),
      "What is the shortest collaboration path between Alice Smith and Igor Petrov in 2015-2025?"
    )
    assertEquals(leaks(p), Nil, p.response.answerText)
    assert(p.response.answerText.contains(StageFailures.PathSearch), p.response.answerText)
  }

  test("C-02: vector, resolver and model failures never show their messages") {
    val failingVectors: VectorIndexPort = new VectorIndexPort {
      private val base = w.env.vectors
      def describeIndex(index: String) = base.describeIndex(index)
      def putVectors(index: String, vectors: Seq[VectorRecord]): Unit =
        base.putVectors(index, vectors)
      def getVectors(index: String, keys: Seq[String], returnData: Boolean) =
        base.getVectors(index, keys, returnData)
      def query(
          index: String,
          vector: Array[Float],
          topK: Int,
          filter: Option[MetadataFilter],
          returnMetadata: Boolean
      ): List[VectorHit] = throw new StoreException(Sensitive, true)
    }
    val retr = new SemanticRetriever(qEmb.port, qEmb.spec, failingVectors, storageConfig.vectors)
    val v = run(workflow(retriever = Some(retr)), Topic)
    assertEquals(leaks(v), Nil, v.response.answerText)
    assert(
      v.response.answerText.contains(StageFailures.TopicCandidates) ||
        v.response.answerText.contains(StageFailures.SemanticUnavailable),
      v.response.answerText
    )
    // the retriever's own Left holds no SDK message either
    assert(
      retr
        .retrieve(
          SemanticDocumentType.ResearcherProfile,
          "KDD",
          1,
          Some(SemanticRetriever.windowFilter(2015, 2025))
        )
        .left
        .exists(e => Secrets.forall(s => !e.contains(s))),
      "retriever Left"
    )
    val links = storageConfig.dynamoDb.entityLinkCacheTable
    val res = run(workflow(stores(kv = kvFailing((t, _) => t == links))), Breadth)
    assertEquals(leaks(res), Nil, res.response.answerText)
    assert(
      res.response.identityNotes.exists(_.contains("could not be looked up (store failure)")),
      res.response.identityNotes.toString
    )
    val failingModel = new LanguageModel {
      val provider: LlmProvider = LlmProvider.Anthropic
      val modelId = "failing-with-text"
      val usage = new LlmUsage
      def complete(r: ModelRequest): Either[ModelFailure, ModelReply] =
        Left(ModelFailure(s"anthropic HTTP 500: $Sensitive", 0, ModelFailure.http(500)))
    }
    val m = run(workflow(model = failingModel), Breadth)
    assertEquals(leaks(m), Nil, m.response.answerText)
    assert(m.response.answerText.contains("model call failed (http-500)"), m.response.answerText)
    assert(m.attempts.forall(_.error.forall(e => Secrets.forall(s => !e.contains(s)))))
  }

  // ---- C-03 ------------------------------------------------------------------------------------

  test("C-03: semantic evidence names its vector key, never the text's S3 URI") {
    val r = run(workflow(), Topic)
    val sem = r.response.bundle.toList
      .flatMap(_.items)
      .filter(_.evidenceType == EvidenceType.SemanticContext)
    assert(sem.nonEmpty, r.response.answerText)
    sem.foreach(i => assertEquals(i.provenance.sourceRecordIds, List(i.subjectIds.head)))
    assert(!shown(r).contains("s3://"), "an S3 URI is in the response")
  }

  // ---- C-05 ------------------------------------------------------------------------------------

  test("C-05: a failed candidate metric read gives no topic ranking, never shifted ranks") {
    val plain = run(workflow(), Topic)
    val ranked = plain.response.bundle.toList
      .flatMap(_.items)
      .filter(i =>
        i.evidenceType == EvidenceType.Metric && EvidenceValues.numbers(i).get("rank").contains(1.0)
      )
    assert(ranked.nonEmpty, plain.response.answerText)
    val first = ranked.head.subjectIds.head
    val metricsTable = storageConfig.dynamoDb.researcherMetricCacheTable
    val r = run(
      workflow(
        stores(kv = kvFailing((t, k) => t == metricsTable && k.exists(_.pk.contains(first))))
      ),
      Topic
    )
    assert(r.abstentions.contains(StageFailures.topicMetrics(1)), r.abstentions.toString)
    val ranks =
      r.response.bundle.toList.flatMap(_.items).flatMap(i => EvidenceValues.numbers(i).get("rank"))
    assertEquals(ranks, Nil, r.response.answerText)
    assertEquals(leaks(r), Nil)
  }

  test("C-05: a failed neighbour edge read is stated in a note") {
    val d = rid("D")
    val g = graphFailing { case GraphQuery.CoauthorEdgeBetween(a, b, _) => a == d || b == d }
    val r = run(workflow(stores(graph = g)), Collaborators)
    assert(r.notes.contains(StageFailures.neighborEdges(1, "Carol King")), r.notes.toString)
    assertEquals(leaks(r), Nil)
  }

  test("C-05: failed bridge-ranking and neighbour-community reads are stated in notes") {
    val metricsTable = storageConfig.dynamoDb.researcherMetricCacheTable
    // rankings are kv queries and still work; every per-researcher metric read (kv get) fails
    val b = run(
      workflow(stores(kv = kvFailing((t, k) => t == metricsTable && k.isDefined))),
      "Which researchers are the strongest bridges between communities in 2015-2025?"
    )
    assert(
      b.notes.exists(_.startsWith("The coauthors in other communities of ")),
      b.notes.toString
    )
    assertEquals(leaks(b), Nil)
    // Carol King's own metrics read; the reads of her top coauthors' metrics fail
    val c = rid("C").value
    val r = run(
      workflow(stores(kv = kvFailing((t, k) => t == metricsTable && k.exists(!_.pk.contains(c))))),
      "Which community is Carol King in during 2015-2025, and is she a bridge between communities?"
    )
    assert(r.notes.exists(_.contains("top coauthors failed (store failure)")), r.notes.toString)
    assertEquals(leaks(r), Nil)
  }

  // ---- C-01 ------------------------------------------------------------------------------------

  test("C-01: a model uncertainty note with digits, identifiers or other names is never shown") {
    val bad = s"Carol King actually has 412 coauthors; see ${rid("I").value} and Igor Petrov."
    val good = "Identity relies on the resolved record."
    val m = new SplitModel(
      w.rule,
      i => {
        val d = FakeSynthesis.render(i)
        Right(
          d.copy(claims = d.claims.zipWithIndex.map { case (c, n) =>
            c.copy(uncertainty = Some(if (n == 0) bad else good))
          }).toJson
            .render()
        )
      }
    )
    val r = run(workflow(model = m), Breadth)
    assert(r.response.claims.size >= 2, r.response.answerText)
    // (hex hashes may hold the digits 412, so the check uses the surrounding words)
    assert(!shown(r).contains("412 coauthors"), r.response.answerText)
    assert(!shown(r).contains("actually has"), r.response.answerText)
    assert(!shown(r).contains(rid("I").value))
    assert(!shown(r).contains("Igor Petrov"))
    assert(r.response.claims(1).uncertainty.exists(_.contains(good)), r.response.claims(1).toString)
    assert(r.notes.exists(_.contains("model uncertainty note(s)")), r.notes.toString)
  }

  // ---- U-3 -------------------------------------------------------------------------------------

  test("U-3: the same request under prompt sets v2 and v3 gets two query run IDs") {
    val req = QueryRequest(Breadth)
    val v3 = workflow().queryRunId(req).fold(e => fail(e), identity)
    val v2 = workflow(prompts = promptsV2).queryRunId(req).fold(e => fail(e), identity)
    assertNotEquals(v2, v3)
    assertEquals(workflow().queryRunId(req), Right(v3))
    assertNotEquals(
      QueryRequest.fingerprint(req, sparkRun, promptsV2.versions),
      QueryRequest.fingerprint(req, sparkRun, w.prompts.versions)
    )
  }

  // ---- U-10 and the number-format hint ---------------------------------------------------------

  /** Planner from the rule planner; the first synthesis reply is cut off at max_tokens. */
  final class TruncatingModel extends LanguageModel {
    val provider: LlmProvider = LlmProvider.Anthropic
    val modelId = "truncating-test-model"
    val usage = new LlmUsage
    val requests = mutable.ListBuffer.empty[ModelRequest]
    private var synth = 0
    def complete(r: ModelRequest): Either[ModelFailure, ModelReply] = synchronized {
      requests += r
      r.fake match {
        case PlannerFakeInput(q) =>
          Right(ModelReply(w.rule.draft(q).toJson.render(), Some(1L), Some(1L), Some("end_turn")))
        case SynthesisFakeInput(i) =>
          synth += 1
          val full = FakeSynthesis.render(i).toJson.render()
          if (synth == 1)
            Right(ModelReply(full.take(40), Some(1L), Some(1L), Some(StopReasons.MaxTokens)))
          else Right(ModelReply(full, Some(1L), Some(1L), Some("end_turn")))
      }
    }
    def synthesisRequests: List[ModelRequest] =
      requests.toList.filter(_.task == ModelTask.Synthesis)
  }

  test(
    "U-10: after a cut-off output the v3 retry asks for at most 8 claims and states the number format"
  ) {
    val m = new TruncatingModel
    val r = run(workflow(model = m), Breadth)
    assert(r.synthesis.usedModel, r.synthesis.fallbackReason.toString)
    val retry = m.synthesisRequests(1).user
    assert(retry.contains(AnswerSynthesis.TruncatedRetryHint), retry.takeRight(600))
    assert(retry.contains("at most 8 claims"))
    assert(retry.contains(AnswerSynthesis.NumberFormatHint))
    assertEquals(r.audit.modelCalls("synthesis").truncatedOutputs, 1L)
    // v2 replays Phase 10: the same retry without the hints
    val m2 = new TruncatingModel
    run(workflow(model = m2, prompts = promptsV2), Breadth)
    val retry2 = m2.synthesisRequests(1).user
    assert(!retry2.contains(AnswerSynthesis.TruncatedRetryHint))
    assert(!retry2.contains(AnswerSynthesis.NumberFormatHint))
    assert(retry2.endsWith("Return only a JSON object that matches answer-claims-v2."))
  }

  // ---- L-1 in the workflow ---------------------------------------------------------------------

  private def withUnmatchedNumber: SplitModel = new SplitModel(
    w.rule,
    i => {
      val d = FakeSynthesis.render(i)
      val c = d.claims.head
      Right(
        d.copy(claims =
          c.copy(numbers =
            c.numbers :+ DraftNumber(987654, c.evidenceIds.head, "value")
          ) :: d.claims.tail
        ).toJson
          .render()
      )
    }
  )

  test(
    "L-1: v3 uses an output with an unmatched bound number, dropped and counted; v2 rejects it"
  ) {
    val m = withUnmatchedNumber
    val r = run(workflow(model = m), Breadth)
    assert(r.synthesis.usedModel, r.synthesis.fallbackReason.toString)
    assertEquals(r.synthesis.attempts.map(_.droppedNumbers), List(Map(NumberFormats.Absent -> 1L)))
    assertEquals(m.usage.synthesis.droppedBoundNumbers.snapshot.get(NumberFormats.Absent), Some(1L))
    assertEquals(r.audit.modelCalls("synthesis").droppedBoundNumbers, 1L)
    assert(r.response.claims.forall(_.status == VerificationStatus.Verified), r.response.answerText)
    assert(r.synthesis.attempts.head.toJson.render().contains("droppedBoundNumbers"))
    val m2 = withUnmatchedNumber
    val r2 = run(workflow(model = m2, prompts = promptsV2), Breadth)
    assert(!r2.synthesis.usedModel)
    assertEquals(r2.synthesis.attempts.head.outcome, ModelAttempt.Invalid)
    assertEquals(r2.synthesis.attempts.head.numberErrors, Map(NumberFormats.Absent -> 1L))
    assert(m2.usage.synthesis.boundNumberErrors.snapshot.get(NumberFormats.Absent).exists(_ >= 1L))
    assertEquals(r2.audit.modelCalls("synthesis").fallbacks, 1L)
  }

  // ---- QueryAudit v2 ---------------------------------------------------------------------------

  test("QueryAudit v2: template IDs, indexes, filter keys, model calls and cache hits are filled") {
    val p = run(workflow(), Path)
    val a = p.audit
    assertEquals(a.schemaVersion, 2)
    assert(a.queryTemplateIds.contains("coauthor_edge_between"), a.queryTemplateIds.toString)
    assert(
      a.indexesQueried.contains(storageConfig.vectors.collaborationPathIndex),
      a.indexesQueried.toString
    )
    assertEquals(a.modelCalls.keySet, Set("planner", "synthesis"))
    assertEquals(a.modelCalls("planner"), ModelCallCounts(1, 0, 0, 0, 0))
    assertEquals(a.modelCalls("synthesis"), ModelCallCounts(1, 0, 0, 0, 0))
    assertEquals(Contract.validate(a), Nil)
    assertEquals(w.env.audits.read(p.queryRunId, 10).map(_.last), Right(a))
    val t = run(workflow(), Topic).audit
    assert(
      t.indexesQueried.contains(
        storageConfig.vectors.indexFor(SemanticDocumentType.ResearcherProfile)
      ),
      t.indexesQueried.toString
    )
    assertEquals(t.metadataFilterKeys, List("windowEndYear", "windowStartYear"))
    assert(t.cacheHits.get(QueryTrace.SemanticContextCache).exists(_ >= 1L), t.cacheHits.toString)
    assert(t.cacheHits.keySet.subsetOf(QueryTrace.CacheNames.toSet))
    assertEquals(Contract.validate(t), Nil)
  }

  // ---- n-3 (Phase 9, tests only) ---------------------------------------------------------------

  test(
    "n-3: a failed ResearcherById read is 'unknown'; above 250 researchers they are 'unchecked'"
  ) {
    val calls = new AtomicInteger(0)
    val g: GraphReadPort => GraphReadPort = base =>
      new GraphReadPort {
        def settings: GraphReadSettings = base.settings
        def run(q: GraphQuery): Either[String, GraphResult] = q match {
          case _: GraphQuery.ResearcherById => calls.incrementAndGet(); Left("store failure")
          case other                        => base.run(other)
        }
      }
    val check = new IdentityCheck(stores(graph = g))
    val empty = ResolverOutput(Nil, Nil, Nil)
    val src = SourceRef("x", sparkRun, "p", "s")
    val people = (0 until 260).toList.map { i =>
      ResearcherRef(
        ResearcherId.fromAnchorMention(MentionId.of(WorkId.fromDblpKey(s"conf/n3/P$i"), 0)),
        s"Person Number$i"
      )
    }
    val facts = people.map(r =>
      MetricFact(r, Full, FullW, CollaborationMetric.DistinctCoauthors, 1, None, None, src, 30)
    )
    val one = check.run(facts.take(1), empty)
    assertEquals(one.outcomes(people.head.id), "unknown")
    assert(one.uncertain.contains(people.head.id))
    calls.set(0)
    val many = check.run(facts, empty)
    assertEquals(calls.get, IdentityCheck.MaxLookups)
    assertEquals(many.unchecked, 260 - IdentityCheck.MaxLookups)
    assertEquals(many.outcomes(people.last.id), IdentityCheck.Unchecked)
    assert(
      IdentityCheck.notes(many, Set.empty).exists(_.contains("was not checked (lookup bound 250)")),
      IdentityCheck.notes(many, Set.empty).toString
    )
  }

  // ---- U-5 -------------------------------------------------------------------------------------

  test("U-5: RuntimeService.local closes the model it built when a later step fails") {
    val cfg = RagFixtures.loadConfig(
      "/config/rag-runtime-local-v2.json",
      Map("SCHOLARCHAIN_CFG_LLM_PROVIDER" -> "bedrock")
    )
    final class ClosingInvoker extends BedrockInvoker with AutoCloseable {
      val closed = new AtomicInteger(0)
      def invoke(modelId: String, jsonBody: String): String = sys.error("never called")
      def close(): Unit = { closed.incrementAndGet(); () }
    }
    val inv = new ClosingInvoker
    val missing = Files.createTempDirectory("no-neptune").resolve("absent")
    val out = scala.util.Try(
      RuntimeService.local(
        cfg,
        w.exportDir,
        missing,
        RuntimeService.LocalOptions(llmWiring = LlmFactory.Wiring(bedrockInvoker = _ => inv))
      )
    )
    assert(out.toOption.forall(_.isLeft), out.toString)
    assertEquals(inv.closed.get, 1)
  }

  // ---- D-01 ------------------------------------------------------------------------------------

  test("D-01: the benchmark runner and the ingest job apply SCHOLARCHAIN_CFG_* overrides") {
    val p = Paths.get(getClass.getResource("/config/rag-runtime-local-v2.json").toURI)
    val env = Map(
      "SCHOLARCHAIN_CFG_STORAGE_VECTOR_BUCKET" -> "override-vector-bucket",
      "HOME" -> "/not/a/config/key"
    )
    val file = BenchmarkRunner.loadConfig(p, Map.empty).fold(e => fail(e), identity)
    assertEquals(file.storage.vectorBucket, "example-vector-bucket")
    val b = BenchmarkRunner.loadConfig(p, env).fold(e => fail(e), identity)
    val i = RagIngestJob.loadConfig(p, env).fold(e => fail(e), identity)
    assertEquals(b.storage.vectorBucket, "override-vector-bucket")
    assertEquals(i.storage.vectorBucket, "override-vector-bucket")
    assertNotEquals(b.fingerprint, file.fingerprint)
    assertEquals(i.fingerprint, b.fingerprint)
    // an unknown SCHOLARCHAIN_CFG_ variable is refused, as by core's loader everywhere
    assert(BenchmarkRunner.loadConfig(p, Map("SCHOLARCHAIN_CFG_NOT_A_KEY" -> "x")).isLeft)
  }
}
