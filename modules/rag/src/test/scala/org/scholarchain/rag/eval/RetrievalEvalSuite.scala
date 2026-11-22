package org.scholarchain.rag.eval

import java.nio.file.{Files, Paths}
import java.util.concurrent.atomic.AtomicLong

import org.scholarchain.core.ResearcherId
import org.scholarchain.rag.RagFixtures.{NoConfigEnv, OverrideBucket}
import org.scholarchain.rag.runtime.{RankedEntry, RetrievalModes, RuntimeFixtures}
import org.scholarchain.storage.ports.{
  GraphQuery,
  GraphReadPort,
  GraphReadSettings,
  GraphResult,
  ObjectRef
}
import org.scholarchain.storage.runtime.RuntimeStores

/** Phase 15, decision 7.4: the three retrieval modes, reciprocal-rank fusion, the metrics, the
  * bounds and the `rag-retrieval-eval` job on the fixture world (local stores, fake embedder, no
  * LLM).
  */
class RetrievalEvalSuite extends munit.FunSuite {
  import RuntimeFixtures._

  override val munitTimeout = scala.concurrent.duration.Duration(300, "s")

  private lazy val w = world()
  private val Ranking = "Which researchers have the most distinct coauthors in 2015-2025?"
  private val Collaborators = "Who are the direct collaborators of Carol King in 2015-2025?"
  private val Topic =
    "Which researchers working on KDD have the most distinct coauthors in 2015-2025?"

  private def modes(s: RuntimeStores = w.env) =
    new RetrievalModes(s, w.ctx, Some(w.retriever), storageConfig.vectors.collaborationPathIndex)

  private def e(id: String, rank: Int) = RankedEntry(id, rank, None)

  private def set(qs: EvalQuestion*): RetrievalEvalSet =
    RetrievalEvalSet("fixture", "0" * 64, RetrievalEvalSet.Rule, qs.toList, Nil)

  private def q(id: String, question: String, targets: List[ResearcherId]) =
    EvalQuestion(id, "fixture", question, Full, targets.map(_.value), Nil)

  private def slowGraph(delayMillis: Long): RuntimeStores = {
    val base = w.env.graph
    val g = new GraphReadPort {
      def settings: GraphReadSettings = base.settings
      def run(q: GraphQuery): Either[String, GraphResult] = {
        Thread.sleep(delayMillis); base.run(q)
      }
    }
    RuntimeStores
      .of(storageConfig, g, w.env.storage.kv, w.env.vectors, w.env.textStore)
      .fold(e => sys.error(e), identity)
  }

  // ---- fusion and metrics --------------------------------------------------------------------

  test("fusion-exp-v1: reciprocal-rank fusion, k = 60, over the union; ties by ID") {
    val fused = RetrievalModes.fuse(List(List(e("x", 1), e("y", 2)), List(e("y", 1), e("z", 2))))
    assertEquals(fused.map(_.researcherId), List("y", "x", "z"))
    assertEqualsDouble(fused.head.score.get, 1.0 / 62 + 1.0 / 61, 1e-12)
    assertEqualsDouble(fused(1).score.get, 1.0 / 61, 1e-12)
    assertEquals(fused.map(_.rank), List(1, 2, 3))
    // an ID in one list only contributes from that list; equal scores order by ID
    assertEquals(
      RetrievalModes.fuse(List(List(e("b", 1)), List(e("a", 1)))).map(_.researcherId),
      List("a", "b")
    )
    assertEquals(RetrievalModes.fuse(Nil), Nil)
    assertEquals(RetrievalModes.FusionVersion, "fusion-exp-v1")
    assertEquals(RetrievalModes.FusionK, 60)
  }

  test("metrics: recall@K with its ceiling, reciprocal rank, target ranks") {
    val list = List(e("a", 1), e("b", 2), e("c", 3))
    val t = Set("b", "d")
    assertEquals(RetrievalMetrics.recallAt(list, t, 1), 0.0)
    assertEquals(RetrievalMetrics.recallAt(list, t, 5), 0.5)
    assertEquals(RetrievalMetrics.ceilingAt(10, 1), 0.1)
    assertEquals(RetrievalMetrics.ceilingAt(5, 10), 1.0)
    assertEquals(RetrievalMetrics.reciprocalRank(list, t), 0.5)
    assertEquals(RetrievalMetrics.reciprocalRank(Nil, t), 0.0)
    assertEquals(RetrievalMetrics.targetRanks(list, List("b", "d")), List(Some(2), None))
  }

  // ---- the modes -----------------------------------------------------------------------------

  test("graph-only: the stored ranking in rank order, from the workflow's stages, no model") {
    val r = modes().graphOnly(Ranking)
    assertEquals(r.operations, List("researcher_ranking"))
    assert(r.entries.nonEmpty)
    assertEquals(r.entries.map(_.rank), (1 to r.entries.size).toList)
    // C and G tie at 3 distinct coauthors; the smaller ID ranks first (as in the workflow)
    assertEquals(r.entries.take(2).map(_.researcherId).toSet, Set(rC.value, rG.value))
    assertEquals(r.entries.head.score, Some(3.0))
    assertEquals(r.abstentionCodes, Nil)
  }

  test("graph-only: direct collaborators list the neighbours, never the asked researcher") {
    val r = modes().graphOnly(Collaborators)
    assert(r.entries.nonEmpty)
    assert(!r.entries.exists(_.researcherId == rC.value), r.entries.toString)
    assert(r.entries.forall(x => ResearcherId.isWellFormed(x.researcherId)))
  }

  test(
    "graph-only: a topic ranking abstains with topic-candidates-failed (no semantic retrieval)"
  ) {
    val r = modes().graphOnly(Topic)
    assertEquals(r.entries, Nil)
    assertEquals(r.abstentionCodes, List("topic-candidates-failed"))
  }

  test("vector-only: researcher-profile hits in the plan's window, one entry per researcher") {
    val r = modes().vectorOnly(Topic, 30).fold(fail(_), identity)
    assertEquals(r.windowLabel, Full)
    assert(r.entries.nonEmpty)
    assertEquals(r.entries.map(_.researcherId).distinct, r.entries.map(_.researcherId))
    assertEquals(r.entries.map(_.rank), (1 to r.entries.size).toList)
    assert(r.entries.map(_.score.get).sliding(2).forall(p => p.size < 2 || p(0) >= p(1)))
    assert(modes().vectorOnly(Topic, 31).isLeft, "topK above vectors.queryTopK is refused")
  }

  test("inGraph: the drift check reads researcher_by_id") {
    assertEquals(modes().inGraph(rC.value), Right(true))
    assertEquals(modes().inGraph("researcher:v1:" + "0" * 32), Right(false))
    assertEquals(modes().inGraph("not-an-id"), Right(false))
  }

  // ---- the evaluation ------------------------------------------------------------------------

  private val opts = EvalOptions(RetrievalEvalSet.Modes, 30, 600000L, 60000L)

  test("every question stays in every mode's denominator; one summary line per mode") {
    val s = set(
      q("f1", Ranking, List(rC, rG)),
      q("f2", Collaborators, List(rB, rD, rG)),
      q("f3", Topic, List(rC))
    )
    val lines = scala.collection.mutable.ListBuffer.empty[String]
    val r = RetrievalEval.run(s, modes(), opts, log = l => lines += l)
    assertEquals(r.stopped, None)
    assertEquals(lines.size, 3)
    assert(lines.forall(_.startsWith("[rag-retrieval-eval] summary mode=")), lines.mkString("\n"))
    RetrievalEvalSet.Modes.foreach { m =>
      assertEquals(r.summaries(m)("questions").num, 3.0)
      assertEquals(r.summaries(m)("scored").num, 3.0)
      assertEquals(r.summaries(m)("ndcg").str, RetrievalEval.NdcgNote)
    }
    // f1: graph-only finds both targets in the top 2
    val g = r.report("questions").arr.head("modes")("graph-only")
    assertEquals(g("recallAt")("5").num, 1.0)
    assertEquals(g("reciprocalRank").num, 1.0)
    // f3: graph-only abstains (topic) and is counted as an abstention, not dropped
    assertEquals(r.summaries("graph-only")("abstentions").num, 1.0)
    assertEquals(r.summaries("graph-only")("empty").num, 1.0)
    assertEquals(r.summaries("fused")("label").str, RetrievalModes.FusionLabel)
    assertEquals(r.summaries("fused")("version").str, "fusion-exp-v1")
    assert(r.summaries("vector-only")("exploratory").bool)
    // drift: every target is in the fixture graph
    r.report("questions").arr.foreach(x => assertEquals(x("drift")("labelsNotInGraph").arr.size, 0))
  }

  test("a mode call over its limit is a timeout: scored 0, counted, the run goes on") {
    // the collaborators question reads the graph (resolver and neighbours); each read takes 400 ms
    val s = set(q("f1", Collaborators, List(rB, rD, rG)))
    val r = RetrievalEval.run(s, modes(slowGraph(400)), opts.copy(questionTimeoutMillis = 100L))
    val g = r.report("questions").arr.head("modes")("graph-only")
    assertEquals(g("error").str, "timeout")
    assertEquals(g("recallAt")("30").num, 0.0)
    assertEquals(r.summaries("graph-only")("errors").num, 1.0)
    assertEquals(r.summaries("graph-only")("questions").num, 1.0)
    // fused still ranks the vector-only list and names the failed input
    val f = r.report("questions").arr.head("modes")("fused")
    assertEquals(f("detail")("inputErrors").arr.map(_.str).toList, List("graph-only"))
  }

  test("the total time limit stops the run: the remaining questions score 0 as time-limit") {
    val ticks = new AtomicLong(0L)
    // every clock read advances 1 s
    val clock = () => ticks.addAndGet(1000000000L)
    val s = set(q("f1", Ranking, List(rC)), q("f2", Ranking, List(rG)), q("f3", Ranking, List(rC)))
    val r = RetrievalEval.run(s, modes(), opts.copy(maxMillis = 5000L), clock)
    assert(r.stopped.exists(_.contains("total time limit")), r.stopped.toString)
    val errs = r.report("questions").arr.map(_("modes")("graph-only")("error"))
    assert(errs.last.str == "time-limit", errs.toString)
    assertEquals(r.summaries("graph-only")("questions").num, 3.0)
  }

  test("options: fused needs both inputs; unknown modes are refused") {
    intercept[IllegalArgumentException](EvalOptions(List("fused"), 30, 1L, 1L))
    intercept[IllegalArgumentException](EvalOptions(List("bm25"), 30, 1L, 1L))
  }

  // ---- the job -------------------------------------------------------------------------------

  private def configPath =
    Paths.get(getClass.getResource("/config/rag-runtime-local-v1.json").toURI).toString

  private def jobArgs(out: java.nio.file.Path, extra: String*): Array[String] =
    Array(
      "--config",
      configPath,
      "--out",
      out.toString,
      "--code-version",
      "0123456789abcdef0123456789abcdef01234567",
      "--attempt-id",
      "20261120T200000Z-0123456789ab-abcd",
      "--graph-run",
      sparkRun.value
    ) ++ extra

  test("job options: the constant names, bounds and refusals") {
    assertEquals(
      RetrievalEvalJob.Required,
      List("config", "out", "code-version", "attempt-id", "graph-run")
    )
    assertEquals(
      RetrievalEvalJob.Optional,
      List("modes", "max-seconds", "question-timeout-seconds")
    )
    val out = Files.createTempDirectory("eval-args").resolve("a")
    val ok = RetrievalEvalJob.parse(jobArgs(out)).fold(fail(_), identity)
    assertEquals(ok.modes, RetrievalEvalSet.Modes)
    assertEquals((ok.maxSeconds, ok.questionTimeoutSeconds), (1200, 60))
    def refused(extra: String*) = RetrievalEvalJob.parse(jobArgs(out, extra*)).isLeft
    assert(refused("--modes", "fused"))
    assert(refused("--modes", "graph-only,bm25"))
    assert(refused("--max-seconds", "59"))
    assert(refused("--max-seconds", "3601"))
    assert(refused("--question-timeout-seconds", "301"))
    assert(refused("--bogus", "1"))
    assert(refused("--modes", "graph-only", "--modes", "vector-only"))
    assert(RetrievalEvalJob.parse(jobArgs(out).updated(9, "run:v2:x")).isLeft)
    assert(RetrievalEvalJob.parse(jobArgs(out).updated(7, "../x")).isLeft)
    assertEquals(
      RetrievalEvalJob.parse(jobArgs(out, "--modes", "vector-only,graph-only")).map(_.modes),
      Right(List("graph-only", "vector-only"))
    )
  }

  private def localStores(storesKind: String = "local") =
    (
        _: org.scholarchain.core.config.ScholarChainConfig,
        _: org.scholarchain.storage.StorageConfig
    ) =>
      Right(new RetrievalEvalJob.EvalStores {
        def kind: String = storesKind
        def runtime: RuntimeStores = w.env
        def objects = w.env.storage.objects
        def close(): Unit = ()
      })

  test("job: report written locally and copied put-if-absent under retrieval-eval/ATTEMPT/") {
    val out = Files.createTempDirectory("eval-job").resolve("attempt")
    val evalSet = set(q("f1", Ranking, List(rC, rG)), q("f2", Collaborators, List(rB, rD, rG)))
    val printed = scala.collection.mutable.ListBuffer.empty[String]
    val r = RetrievalEvalJob.execute(
      RetrievalEvalJob.parse(jobArgs(out)).fold(fail(_), identity),
      localStores(),
      hooks = RetrievalEvalJob.Hooks(
        print = l => printed += l,
        evalSet = () => Right(evalSet),
        configEnv = NoConfigEnv
      )
    )
    assertEquals(r.report("status").str, "completed", r.report.render(indent = 2))
    assert(r.copyOk, r.reportCopy.toString)
    assertEquals(
      r.reportCopy("object").str,
      "s3://{data-bucket}/semantic-context/retrieval-eval/20261120T200000Z-0123456789ab-abcd/report.json"
    )
    val local = Files.readAllBytes(out.resolve("report.json"))
    val zone = storageConfig.zones("semantic-context")
    val stored = w.env.storage.objects
      .get(
        ObjectRef(
          zone.bucket,
          RetrievalEvalJob.reportKey(zone.prefix, "20261120T200000Z-0123456789ab-abcd")
        )
      )
    assert(stored.exists(java.util.Arrays.equals(_, local)))
    assertEquals(r.report("llmCalls").num, 0.0)
    assert(r.report("exploratory").bool)
    assert(r.report("label").str.startsWith("EXPLORATORY"))
    assertEquals(printed.count(_.startsWith("[rag-retrieval-eval] summary mode=")), 3)
    assert(r.report("embedderUsage")("requests").num >= 2.0)
    // a second attempt with the same ID meets the stored copy: different bytes are never overwritten
    val again = RetrievalEvalJob.execute(
      RetrievalEvalJob.parse(jobArgs(out.resolveSibling("attempt-2"))).fold(fail(_), identity),
      localStores(),
      hooks = RetrievalEvalJob.Hooks(
        print = _ => (),
        evalSet = () => Right(evalSet),
        configEnv = NoConfigEnv
      )
    )
    assert(!again.copyOk || again.reportCopy("outcome").str == "already present")
  }

  test("job: the fake embedder is refused with AWS stores; the local report still says why") {
    val out = Files.createTempDirectory("eval-job-aws").resolve("a")
    val r = RetrievalEvalJob.execute(
      RetrievalEvalJob.parse(jobArgs(out)).fold(fail(_), identity),
      localStores("aws"),
      hooks = RetrievalEvalJob.Hooks(
        print = _ => (),
        evalSet = () => Right(set(q("f1", Ranking, List(rC)))),
        configEnv = NoConfigEnv
      )
    )
    assert(r.report("status").str.contains("fake embedder is refused"), r.report("status").str)
    assert(Files.exists(out.resolve("report.json")))
    intercept[RuntimeException](
      RetrievalEvalJob.execute(
        RetrievalEvalJob.parse(jobArgs(out)).fold(fail(_), identity),
        localStores(),
        hooks = RetrievalEvalJob.Hooks(print = _ => (), configEnv = NoConfigEnv)
      )
    )
  }

  // ---- P16-L-16: the configuration environment is passed explicitly ---------------------------

  private def copyRun(attempt: String, configEnv: Map[String, String]) = {
    val out = Files.createTempDirectory("eval-job-env").resolve("attempt")
    RetrievalEvalJob.execute(
      RetrievalEvalJob.parse(jobArgs(out).updated(7, attempt)).fold(fail(_), identity),
      localStores(),
      hooks = RetrievalEvalJob.Hooks(
        print = _ => (),
        evalSet = () => Right(set(q("f1", Ranking, List(rC, rG)))),
        configEnv = () => configEnv
      )
    )
  }

  test("P16-L-16: the report copy goes to the semantic-context bucket of the given environment") {
    val zone = storageConfig.zones("semantic-context")
    assertNotEquals(zone.bucket, OverrideBucket)
    val overrides = Map(
      org.scholarchain.core.config.ConfigKey
        .envVarFor("objectStore.semanticContextBucket") -> OverrideBucket,
      org.scholarchain.core.config.ConfigKey.envVarFor("storage.graphBucket") -> OverrideBucket
    )
    // an override passed to the job applies (and is masked in the report)
    val a = "20261122T100000Z-0123456789ab-ovr1"
    val o = copyRun(a, overrides)
    assert(o.completed && o.copyOk, o.report.render(indent = 2))
    val key = RetrievalEvalJob.reportKey(zone.prefix, a)
    assert(w.env.storage.objects.get(ObjectRef(OverrideBucket, key)).nonEmpty)
    assert(w.env.storage.objects.get(ObjectRef(zone.bucket, key)).isEmpty)
    assert(!o.report.render().contains(OverrideBucket))
    // no override: the file's bucket, whatever the shell exports
    val b = "20261122T100000Z-0123456789ab-none"
    val n = copyRun(b, Map.empty)
    assert(n.completed && n.copyOk, n.report.render(indent = 2))
    val keyB = RetrievalEvalJob.reportKey(zone.prefix, b)
    assert(w.env.storage.objects.get(ObjectRef(zone.bucket, keyB)).nonEmpty)
    assert(w.env.storage.objects.get(ObjectRef(OverrideBucket, keyB)).isEmpty)
  }

  test("P16-L-16: the production default of the seam is the process environment") {
    // compared, never printed (the shell may hold real identifiers)
    assert(
      RetrievalEvalJob.Hooks().configEnv() == sys.env,
      "Hooks().configEnv is not the process environment"
    )
  }
}
