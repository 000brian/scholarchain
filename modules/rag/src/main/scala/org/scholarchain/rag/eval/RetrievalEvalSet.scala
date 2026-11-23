package org.scholarchain.rag.eval

import java.nio.charset.StandardCharsets

import scala.util.control.NonFatal

import org.scholarchain.core.{CodePointOrder, ResearcherId}

/** One question of the retrieval evaluation universe: a benchmark question with at least one
  * labelled answer-target researcher.
  *
  * @param targets
  *   the answer targets, in label order: researchers the expected answer must name beyond those the
  *   question names (`expected.researcherIds`)
  * @param byDesignWeak
  *   modes that cannot address this question by design (still scored, marked)
  */
final case class EvalQuestion(
    id: String,
    category: String,
    question: String,
    windowLabel: String,
    targets: List[String],
    byDesignWeak: List[String]
) {
  def toJson: ujson.Obj = ujson.Obj(
    "id" -> id,
    "category" -> category,
    "question" -> question,
    "windowLabel" -> windowLabel,
    "targets" -> ujson.Arr.from(targets.map(ujson.Str(_))),
    "byDesignWeak" -> ujson.Arr.from(byDesignWeak.map(ujson.Str(_)))
  )
}

/** A benchmark question outside the universe, with the reason (never scored). */
final case class NotComparable(id: String, category: String, reason: String) {
  def toJson: ujson.Obj = ujson.Obj("id" -> id, "category" -> category, "reason" -> reason)
}

/** The evaluation set of `rag-retrieval-eval` (Phase 15, decision 7.4): derived from benchmark-v1
  * by [[RetrievalEvalSet.derive]], packaged as the main resource [[RetrievalEvalSet.Resource]] (a
  * test checks that the resource equals the derivation from the test copy of benchmark-v1 and that
  * the test copy's sha256 is [[benchmarkSha256]]).
  *
  * Unit: the canonical researcher ID. The universe is fixed by the rule; it is never widened and no
  * label is added.
  */
final case class RetrievalEvalSet(
    benchmarkId: String,
    benchmarkSha256: String,
    rule: List[String],
    questions: List[EvalQuestion],
    notComparable: List[NotComparable]
) {

  /** Fewer than [[RetrievalEvalSet.MinNonExploratory]] questions: every table says exploratory. */
  def exploratory: Boolean = questions.size < RetrievalEvalSet.MinNonExploratory

  def labelCount: Int = questions.map(_.targets.size).sum

  def toJson: ujson.Obj = ujson.Obj(
    "format" -> RetrievalEvalSet.Format,
    "formatVersion" -> 1,
    "benchmarkId" -> benchmarkId,
    "benchmarkSha256" -> benchmarkSha256,
    "rule" -> ujson.Arr.from(rule.map(ujson.Str(_))),
    "questions" -> ujson.Arr.from(questions.map(_.toJson)),
    "notComparable" -> ujson.Arr.from(notComparable.map(_.toJson))
  )
}

object RetrievalEvalSet {
  val Format = "scholarchain.rag.retrieval-eval-set"

  /** Classpath location of the packaged set. */
  val Resource = "/org/scholarchain/rag/eval/benchmark-v1-eval-set.json"

  /** Decision 7.4: below this many questions in the universe the E result is exploratory. */
  val MinNonExploratory = 15

  val GraphOnly = "graph-only"
  val VectorOnly = "vector-only"
  val Fused = "fused"
  val Modes: List[String] = List(GraphOnly, VectorOnly, Fused)

  /** The derivation rule, stated in the set and the report. */
  val Rule: List[String] = List(
    "unit: canonical researcher ID",
    "answer targets: the researchers of the expected values of kind 'ranking' (by rank), 'edge' (both endpoints, in order) and 'topicRanked' (the top expected.limit, default 10 when the question states none, of the labelled pool, value descending then researcher ID by code point: a derivation of this evaluation set from the pool, not a benchmark label, since the benchmark scorer defines no top-limit target set), minus the researchers the question names (expected.researcherIds); distinct, in that order",
    "a question is in the universe when it has at least one answer target; every other question is not-comparable (labelled only with works, paths, numbers or groups, or an expected abstention), listed with its reason, never scored",
    "by design weak: vector-only on 'ranking' and 'edge' targets (a similarity search cannot rank by a stored metric or read an edge); graph-only on 'topicRanked' targets (its topic filter needs semantic candidates, which graph-only does not use)"
  )

  private val TargetKinds = Set("ranking", "edge", "topicRanked")

  /** Derives the set from benchmark-v1's bytes. Left: the file is not a benchmark. */
  def derive(benchmarkBytes: Array[Byte]): Either[String, RetrievalEvalSet] =
    try {
      val j = ujson.read(new String(benchmarkBytes, StandardCharsets.UTF_8))
      val sha = org.scholarchain.core.Hashing.sha256Hex(benchmarkBytes)
      val all = j("questions").arr.toList.map(derive1)
      Right(
        RetrievalEvalSet(
          j("benchmarkId").str,
          sha,
          Rule,
          all.collect { case Left(q) => q },
          all.collect { case Right(n) => n }
        )
      )
    } catch { case NonFatal(e) => Left(s"not a benchmark: ${e.getClass.getSimpleName}") }

  private def derive1(q: ujson.Value): Either[EvalQuestion, NotComparable] = {
    val id = q("id").str
    val category = q("category").str
    val exp = q("expected")
    val named = exp.obj.get("researcherIds").map(_.arr.map(_.str).toSet).getOrElse(Set.empty)
    val values = exp.obj.get("values").map(_.arr.toList).getOrElse(Nil)
    val limit = exp.obj.get("limit").flatMap(_.numOpt).map(_.toInt)
    val kinds = values.flatMap(_.obj.get("kind").map(_.str)).distinct
    val ids: List[String] = values.flatMap { v =>
      v("kind").str match {
        case "ranking"     => List((v("rank").num.toInt, v("researcherId").str))
        case "edge"        => List((0, v("a").str), (0, v("b").str))
        case "topicRanked" =>
          v("pool").obj.toList
            .map { case (k, n) => (k, n.num) }
            .sortWith { case ((a, va), (b, vb)) =>
              if (va != vb) va > vb else CodePointOrder.ordering.lt(a, b)
            }
            .take(limit.getOrElse(10))
            .zipWithIndex
            .map { case ((k, _), i) => (i + 1, k) }
        case _ => Nil
      }
    } match {
      case xs if values.exists(_("kind").str == "ranking") =>
        // ranking values sort by rank; other kinds keep label order
        val (r, o) = xs.partition(_._1 > 0)
        r.sortBy(_._1).map(_._2) ++ o.map(_._2)
      case xs => xs.map(_._2)
    }
    val targets = ids.filterNot(named).filter(ResearcherId.isWellFormed).distinct
    if (targets.nonEmpty) {
      val weak =
        (if (kinds.exists(Set("ranking", "edge"))) List(VectorOnly) else Nil) ++
          (if (kinds.contains("topicRanked")) List(GraphOnly) else Nil)
      Left(
        EvalQuestion(
          id,
          category,
          q("question").str,
          exp("windowLabel").str,
          targets,
          weak
        )
      )
    } else {
      val reason =
        if (exp.obj.get("abstain").exists(_.bool)) "abstention expected; no researcher label"
        else if (exp.obj.contains("path")) "labelled with a path only (no answer-target researcher)"
        else if (kinds.exists(TargetKinds)) "only the named subjects are labelled"
        else if (kinds.nonEmpty) s"labelled only with ${kinds.mkString(", ")} values"
        else "no labelled value"
      Right(NotComparable(id, category, reason))
    }
  }

  def fromJson(j: ujson.Value): Either[String, RetrievalEvalSet] =
    try
      Right(
        RetrievalEvalSet(
          j("benchmarkId").str,
          j("benchmarkSha256").str,
          j("rule").arr.toList.map(_.str),
          j("questions").arr.toList.map(q =>
            EvalQuestion(
              q("id").str,
              q("category").str,
              q("question").str,
              q("windowLabel").str,
              q("targets").arr.toList.map(_.str),
              q("byDesignWeak").arr.toList.map(_.str)
            )
          ),
          j("notComparable").arr.toList.map(n =>
            NotComparable(n("id").str, n("category").str, n("reason").str)
          )
        )
      ).filterOrElse(
        s => j("format").str == Format && s.questions.forall(_.targets.nonEmpty),
        "not a retrieval evaluation set"
      )
    catch {
      case NonFatal(e) => Left(s"not a retrieval evaluation set: ${e.getClass.getSimpleName}")
    }

  /** The packaged set (main resource). */
  def load(): Either[String, RetrievalEvalSet] =
    Option(getClass.getResourceAsStream(Resource)) match {
      case None     => Left(s"resource $Resource is missing")
      case Some(in) =>
        try fromJson(ujson.read(new String(in.readAllBytes(), StandardCharsets.UTF_8)))
        finally in.close()
    }
}
