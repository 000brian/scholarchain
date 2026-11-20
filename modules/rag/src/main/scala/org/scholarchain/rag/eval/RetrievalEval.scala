package org.scholarchain.rag.eval

import java.util.concurrent.{
  Callable,
  ExecutionException,
  ExecutorService,
  Executors,
  ThreadFactory,
  TimeUnit,
  TimeoutException
}

import scala.util.control.NonFatal

import org.scholarchain.rag.runtime.{AbstentionCodes, RankedEntry, RetrievalModes}

/** Retrieval metrics over one ranked list and one target set (decision 7.4). */
object RetrievalMetrics {

  /** The K values of every recall@K. */
  val Ks: List[Int] = List(1, 5, 10, 30)

  /** recall@K: targets in the top K over all targets (0 when there is no target). */
  def recallAt(entries: List[RankedEntry], targets: Set[String], k: Int): Double =
    if (targets.isEmpty) 0.0
    else entries.take(k).count(e => targets.contains(e.researcherId)).toDouble / targets.size

  /** The best recall@K any list can reach: min(K, |T|) / |T|. */
  def ceilingAt(targets: Int, k: Int): Double =
    if (targets == 0) 0.0 else math.min(k, targets).toDouble / targets

  /** 1 / rank of the first target in the list; 0 when none is listed. */
  def reciprocalRank(entries: List[RankedEntry], targets: Set[String]): Double =
    entries.find(e => targets.contains(e.researcherId)).map(1.0 / _.rank).getOrElse(0.0)

  /** The list rank of each target (label order), or None when the list does not hold it. */
  def targetRanks(entries: List[RankedEntry], targets: List[String]): List[Option[Int]] = {
    val byId = entries.map(e => e.researcherId -> e.rank).toMap
    targets.map(byId.get)
  }

  def stats(xs: Seq[Double]): ujson.Obj =
    if (xs.isEmpty) ujson.Obj("n" -> 0)
    else {
      val s = xs.sorted
      def pct(p: Double) = s(math.min(s.size - 1, math.ceil(p * s.size).toInt - 1).max(0))
      ujson.Obj(
        "n" -> s.size,
        "min" -> s.head,
        "median" -> pct(0.5),
        "p95" -> pct(0.95),
        "max" -> s.last,
        "mean" -> s.sum / s.size
      )
    }
}

/** Bounds and modes of one evaluation.
  *
  * @param maxMillis
  *   total time limit: questions not started by then are recorded as `time-limit` (scored 0) and
  *   the run is `stopped`
  * @param questionTimeoutMillis
  *   limit of one mode call on one question: a call still running is recorded as `timeout` (scored
  *   0) and abandoned (its thread is a daemon; graph reads are bounded by
  *   `runtime.graphTimeoutMillis`)
  */
final case class EvalOptions(
    modes: List[String],
    topK: Int,
    maxMillis: Long,
    questionTimeoutMillis: Long
) {
  require(modes.nonEmpty && modes.forall(RetrievalEvalSet.Modes.contains), "unknown mode")
  require(
    !modes.contains(RetrievalEvalSet.Fused) ||
      (modes.contains(RetrievalEvalSet.GraphOnly) && modes.contains(RetrievalEvalSet.VectorOnly)),
    "fused needs graph-only and vector-only"
  )
  require(topK >= 1, "topK must be >= 1")
  require(maxMillis > 0 && questionTimeoutMillis > 0, "limits must be > 0")
}

/** The evaluation itself (no store wiring): every question of the set in every mode, the metrics
  * and the per-mode summaries. Each mode keeps every universe question in its denominator: an
  * error, a timeout or an empty list scores 0 and is counted, never dropped.
  */
object RetrievalEval {
  import RetrievalEvalSet.{Fused, GraphOnly, VectorOnly}

  /** Codes of graph-only stage failures (counted as errors; other codes count as abstentions).
    * `topic-candidates-failed` is not one: graph-only has no semantic retrieval by design, so a
    * topic filter always abstains with it (an abstention of a by-design-weak question).
    */
  val FailureCodes: Set[String] = Set(
    AbstentionCodes.MetricsReadFailed,
    AbstentionCodes.RankingReadFailed,
    AbstentionCodes.TopicMetricsFailed,
    AbstentionCodes.PathQueryFailed,
    AbstentionCodes.EdgeLookupFailed,
    AbstentionCodes.NeighborLookupFailed
  )

  val NdcgNote = "not computed: benchmark-v1 has no graded relevance labels"

  /** One mode on one question. */
  final case class ModeOutcome(
      entries: List[RankedEntry],
      latencyMillis: Long,
      error: Option[String],
      abstentionCodes: List[String],
      detail: ujson.Obj
  ) {
    def empty: Boolean = entries.isEmpty
  }

  final case class Result(
      report: ujson.Obj,
      stopped: Option[String],
      summaries: Map[String, ujson.Obj]
  )

  private def daemonPool(): ExecutorService =
    Executors.newCachedThreadPool(new ThreadFactory {
      def newThread(r: Runnable): Thread = {
        val t = new Thread(r, "retrieval-eval")
        t.setDaemon(true)
        t
      }
    })

  def run(
      set: RetrievalEvalSet,
      modes: RetrievalModes,
      opts: EvalOptions,
      clock: () => Long = () => System.nanoTime(),
      log: String => Unit = _ => ()
  ): Result = {
    val pool = daemonPool()
    val t0 = clock()
    def elapsedMillis = (clock() - t0) / 1000000L

    /** Runs `f` under the per-question limit; Right(value, millis) or Left(error code). */
    def bounded[A](f: => A): (Either[String, A], Long) = {
      val s = clock()
      val fut = pool.submit(new Callable[A] { def call(): A = f })
      val r =
        try Right(fut.get(opts.questionTimeoutMillis, TimeUnit.MILLISECONDS))
        catch {
          case _: TimeoutException =>
            fut.cancel(true); Left("timeout")
          case e: ExecutionException =>
            Left(s"exception:${Option(e.getCause).getOrElse(e).getClass.getSimpleName}")
          case NonFatal(e) => Left(s"exception:${e.getClass.getSimpleName}")
        }
      (r, math.max(0L, (clock() - s) / 1000000L))
    }

    var stopped: Option[String] = None
    val perQuestion = List.newBuilder[(EvalQuestion, Map[String, ModeOutcome], ujson.Obj)]
    try
      set.questions.foreach { q =>
        val outcomes: Map[String, ModeOutcome] =
          if (stopped.isDefined || elapsedMillis > opts.maxMillis) {
            if (stopped.isEmpty)
              stopped = Some(s"total time limit of ${opts.maxMillis} ms reached before ${q.id}")
            opts.modes
              .map(m => m -> ModeOutcome(Nil, 0L, Some("time-limit"), Nil, ujson.Obj()))
              .toMap
          } else {
            val g =
              if (!opts.modes.contains(GraphOnly)) None
              else
                Some(bounded(modes.graphOnly(q.question)) match {
                  case (Right(r), ms) =>
                    val failures = r.abstentionCodes.filter(FailureCodes)
                    ModeOutcome(
                      r.entries,
                      ms,
                      failures.headOption.map(c => s"stage:$c"),
                      r.abstentionCodes,
                      ujson.Obj(
                        "operations" -> ujson.Arr.from(r.operations.map(ujson.Str(_))),
                        "windowLabel" -> r.windowLabel
                      )
                    )
                  case (Left(e), ms) => ModeOutcome(Nil, ms, Some(e), Nil, ujson.Obj())
                })
            val v =
              if (!opts.modes.contains(VectorOnly)) None
              else
                Some(bounded(modes.vectorOnly(q.question, opts.topK)) match {
                  case (Right(Right(r)), ms) =>
                    ModeOutcome(
                      r.entries,
                      ms,
                      None,
                      Nil,
                      ujson.Obj(
                        "hits" -> r.hits,
                        "windowLabel" -> r.windowLabel,
                        "droppedIncompatibleModel" -> r.droppedIncompatibleModel,
                        "droppedMalformedMetadata" -> r.droppedMalformedMetadata
                      )
                    )
                  case (Right(Left(e)), ms) =>
                    ModeOutcome(Nil, ms, Some(s"retrieval:$e"), Nil, ujson.Obj())
                  case (Left(e), ms) => ModeOutcome(Nil, ms, Some(e), Nil, ujson.Obj())
                })
            val f =
              if (!opts.modes.contains(Fused)) None
              else {
                val inputs = List(g, v).flatten
                val s = clock()
                val fused = RetrievalModes.fuse(inputs.map(_.entries))
                val own = math.max(0L, (clock() - s) / 1000000L)
                val inputErrors =
                  List(GraphOnly -> g, VectorOnly -> v).collect {
                    case (m, Some(o)) if o.error.isDefined => m
                  }
                Some(
                  ModeOutcome(
                    fused,
                    inputs.map(_.latencyMillis).sum + own,
                    if (inputErrors.size == 2) Some("inputs-failed") else None,
                    Nil,
                    ujson.Obj(
                      "fusionMillis" -> own.toDouble,
                      "inputErrors" -> ujson.Arr.from(inputErrors.map(ujson.Str(_)))
                    )
                  )
                )
              }
            List(GraphOnly -> g, VectorOnly -> v, Fused -> f).collect { case (m, Some(o)) =>
              m -> o
            }.toMap
          }
        // drift (R2 of the 3r design): labels the loaded graph does not hold stay in the denominator
        val drift =
          if (stopped.isDefined) ujson.Obj("checked" -> false)
          else {
            val checks = q.targets.map(t => t -> bounded(modes.inGraph(t))._1)
            ujson.Obj(
              "checked" -> true,
              "labelsNotInGraph" -> ujson.Arr.from(checks.collect { case (t, Right(Right(false))) =>
                ujson.Str(t)
              }),
              "labelsUnchecked" -> checks.count {
                case (_, Right(Right(_))) => false
                case _                    => true
              }
            )
          }
        perQuestion += ((q, outcomes, drift))
      }
    finally pool.shutdownNow()

    val rows = perQuestion.result()
    val questionsJson = ujson.Arr.from(rows.map { case (q, outcomes, drift) =>
      val targets = q.targets.toSet
      ujson.Obj(
        "id" -> q.id,
        "category" -> q.category,
        "windowLabel" -> q.windowLabel,
        "targets" -> q.targets.size,
        "byDesignWeak" -> ujson.Arr.from(q.byDesignWeak.map(ujson.Str(_))),
        "drift" -> drift,
        "modes" -> ujson.Obj.from(
          opts.modes.flatMap(m =>
            outcomes.get(m).map { o =>
              m -> ujson.Obj(
                "listLength" -> o.entries.size,
                "latencyMillis" -> o.latencyMillis.toDouble,
                "error" -> o.error.map(ujson.Str(_)).getOrElse(ujson.Null),
                "empty" -> o.empty,
                "abstentionCodes" -> ujson.Arr.from(o.abstentionCodes.map(ujson.Str(_))),
                "byDesignWeak" -> q.byDesignWeak.contains(m),
                "recallAt" -> ujson.Obj.from(
                  RetrievalMetrics.Ks.map(k =>
                    k.toString -> ujson.Num(RetrievalMetrics.recallAt(o.entries, targets, k))
                  )
                ),
                "recallCeilingAt" -> ujson.Obj.from(
                  RetrievalMetrics.Ks
                    .map(k => k.toString -> ujson.Num(RetrievalMetrics.ceilingAt(targets.size, k)))
                ),
                "reciprocalRank" -> RetrievalMetrics.reciprocalRank(o.entries, targets),
                "targetRanks" -> ujson.Arr.from(
                  RetrievalMetrics
                    .targetRanks(o.entries, q.targets)
                    .map(_.map(r => ujson.Num(r.toDouble)).getOrElse(ujson.Null))
                ),
                "top" -> ujson.Arr.from(o.entries.take(opts.topK).map(_.toJson)),
                "detail" -> o.detail
              )
            }
          )
        )
      )
    })

    val n = set.questions.size
    val summaries: Map[String, ujson.Obj] = opts.modes.map { m =>
      val os = rows.flatMap { case (q, outcomes, _) => outcomes.get(m).map(q -> _) }
      def mean(f: (EvalQuestion, ModeOutcome) => Double): Double =
        if (n == 0) 0.0 else os.map { case (q, o) => f(q, o) }.sum / n
      val errors = os.count(_._2.error.isDefined)
      val empty = os.count(_._2.empty)
      val abstentions =
        os.count { case (_, o) => o.empty && o.abstentionCodes.exists(c => !FailureCodes(c)) }
      val recall = RetrievalMetrics.Ks.map(k =>
        k -> mean((q, o) => RetrievalMetrics.recallAt(o.entries, q.targets.toSet, k))
      )
      val s = ujson.Obj(
        "mode" -> m,
        "questions" -> n,
        "scored" -> os.size,
        "exploratory" -> set.exploratory,
        "recallAt" -> ujson.Obj.from(recall.map { case (k, v) => k.toString -> ujson.Num(v) }),
        "recallCeilingAt" -> ujson.Obj.from(
          RetrievalMetrics.Ks.map(k =>
            k.toString -> ujson.Num(mean((q, _) => RetrievalMetrics.ceilingAt(q.targets.size, k)))
          )
        ),
        "mrr" -> mean((q, o) => RetrievalMetrics.reciprocalRank(o.entries, q.targets.toSet)),
        "ndcg" -> NdcgNote,
        "errors" -> errors,
        "empty" -> empty,
        "abstentions" -> abstentions,
        "byDesignWeakQuestions" -> os.count(_._1.byDesignWeak.contains(m)),
        "latencyMillis" -> RetrievalMetrics.stats(os.map(_._2.latencyMillis.toDouble)),
        "listLength" -> RetrievalMetrics.stats(os.map(_._2.entries.size.toDouble))
      )
      if (m == Fused) {
        s("version") = RetrievalModes.FusionVersion
        s("k") = RetrievalModes.FusionK
        s("label") = RetrievalModes.FusionLabel
      }
      log(
        summaryLine(
          m,
          n,
          set.exploratory,
          recall,
          s("mrr").num,
          errors,
          empty,
          abstentions,
          os.map(_._2.latencyMillis)
        )
      )
      m -> s
    }.toMap

    val report = ujson.Obj(
      "questions" -> questionsJson,
      "modes" -> ujson.Obj.from(opts.modes.map(m => m -> summaries(m))),
      "evaluationMillis" -> elapsedMillis.toDouble
    )
    Result(report, stopped, summaries)
  }

  /** The one summary line per mode (in the task log). */
  def summaryLine(
      mode: String,
      questions: Int,
      exploratory: Boolean,
      recall: List[(Int, Double)],
      mrr: Double,
      errors: Int,
      empty: Int,
      abstentions: Int,
      latencies: Seq[Long]
  ): String = {
    def f(x: Double) = f"$x%.4f"
    val med =
      if (latencies.isEmpty) "n/a"
      else latencies.sorted.apply((latencies.size - 1) / 2).toString
    s"[rag-retrieval-eval] summary mode=$mode questions=$questions exploratory=$exploratory " +
      recall.map { case (k, v) => s"recall@$k=${f(v)}" }.mkString(" ") +
      s" mrr=${f(mrr)} errors=$errors empty=$empty abstentions=$abstentions latencyMedianMillis=$med"
  }
}
