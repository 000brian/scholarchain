package org.scholarchain.services

import scala.collection.mutable.ListBuffer
import scala.util.Try

import org.scholarchain.core.ContentHash
import org.scholarchain.core.config.{NamedWindow, RuntimeSettings}
import org.scholarchain.rag.runtime.{PlanBounds, QueryRequest}
import org.scholarchain.storage.jobs.{JobMode, JobRequest}

/** A validated `POST /v1/query` body after defaults (decision 7c).
  *
  * @param question
  *   canonical form (`QueryRequest.canonicalQuestion`)
  * @param windowLabel
  *   the configured window whose years equal `constraints.yearFrom`/`yearTo`
  * @param topic
  *   the one `constraints.topics` entry, canonical
  * @param retrieval
  *   the `retrieval.*` values present (validated, not applied)
  * @param analysis
  *   the `analysis.*` values present (validated by type, not applied)
  * @param storedNotes
  *   the notes stored on a job ([[ValidatedQuery.ofJob]]); None for a parsed request, whose notes
  *   are derived from `retrieval` and `analysis`
  */
final case class ValidatedQuery(
    question: String,
    windowLabel: Option[String],
    topic: Option[String],
    mode: JobMode,
    includeEvidence: Boolean,
    retrieval: Map[String, Int],
    analysis: Map[String, ujson.Value],
    storedNotes: Option[List[String]] = None
) {

  /** What reaches rag: the question, the window label and the topic; nothing else. */
  def toQueryRequest: QueryRequest = QueryRequest(question, windowLabel, topic)

  /** The stored job request, with exactly the notes this request's result carries (decision 7c: a
    * worker's completed result equals the sync result for the same request).
    */
  def toJobRequest: JobRequest = JobRequest(question, windowLabel, topic, includeEvidence, notes)

  /** Every accepted field that this phase does not apply (the plan comes from the question). */
  def notes: List[String] =
    storedNotes.getOrElse(
      (retrieval.keys.map("retrieval." + _) ++ analysis.keys.map("analysis." + _)).toList.sorted
        .map(f => s"$f was accepted but not applied; the plan comes from the question")
    )

  /** sha256 of the canonical JSON of the whole validated request after defaults, keys sorted. */
  def fingerprint: String = {
    def opt(v: Option[ujson.Value]): ujson.Value = v.getOrElse(ujson.Null)
    val json = ujson.Obj(
      "question" -> question,
      "windowLabel" -> opt(windowLabel.map(ujson.Str(_))),
      "topic" -> opt(topic.map(ujson.Str(_))),
      "mode" -> mode.wire,
      "output" -> ujson.Obj(
        "includeEvidence" -> includeEvidence,
        "includeScores" -> true,
        "includeUncertainty" -> true
      ),
      "retrieval" -> ujson.Obj.from(
        QueryRequestParser.RetrievalFields.map(f =>
          f -> opt(retrieval.get(f).map(n => ujson.Num(n)))
        )
      ),
      "analysis" -> ujson.Obj.from(
        QueryRequestParser.AnalysisFields.map(f => f -> opt(analysis.get(f)))
      )
    )
    ContentHash.of(Json.canonical(json))
  }
}

object ValidatedQuery {

  /** The validated form of a stored job request (a worker runs it). The retrieval and analysis
    * values are not stored; the notes they produced are, in the same order, so the worker's result
    * carries the same notes as the sync result. Not used for fingerprints.
    */
  def ofJob(r: JobRequest, mode: JobMode): ValidatedQuery =
    ValidatedQuery(
      r.question,
      r.windowLabel,
      r.topic,
      mode,
      r.includeEvidence,
      Map.empty,
      Map.empty,
      Some(r.notes)
    )
}

/** Validates the `POST /v1/query` body. Field problems are 400 `INVALID_REQUEST`; valid JSON with a
  * constraint the runtime cannot apply is 422 `UNSUPPORTED_CONSTRAINT`. Unknown fields anywhere are
  * 400.
  */
final class QueryRequestParser(
    windows: List[NamedWindow],
    runtime: RuntimeSettings,
    vectorTopK: Int
) {
  import QueryRequestParser._

  def parse(body: String): Either[ApiError, ValidatedQuery] = {
    val json =
      if (!Json.depthWithin(body, ServiceLimits.MaxJsonDepth)) None
      else Try(ujson.read(body)).toOption
    json match {
      case None               => Left(ApiError(ErrorCode.INVALID_JSON))
      case Some(o: ujson.Obj) => validate(o)
      case Some(_)            => Left(ApiError.invalid("(body)", "must be a JSON object"))
    }
  }

  private def validate(o: ujson.Obj): Either[ApiError, ValidatedQuery] = {
    val bad = ListBuffer.empty[(String, String)]
    val unsupported = ListBuffer.empty[(String, String)]
    var windowMismatch = false

    def unknown(prefix: String, obj: ujson.Obj, allowed: List[String]): Unit =
      obj.value.keys.toList.sorted.filterNot(allowed.contains).foreach { k =>
        bad += (s"$prefix$k" -> "unknown field")
      }
    def objectAt(name: String): Option[ujson.Obj] = o.value.get(name) match {
      case None | Some(ujson.Null) => None
      case Some(x: ujson.Obj)      => Some(x)
      case Some(_)                 => bad += (name -> "must be an object"); None
    }
    def int(field: String, v: ujson.Value): Option[Int] = v match {
      case ujson.Num(n) if n.isWhole && n >= Int.MinValue && n <= Int.MaxValue => Some(n.toInt)
      case _ => bad += (field -> "must be an integer"); None
    }
    def bool(field: String, v: ujson.Value): Option[Boolean] = v match {
      case ujson.Bool(b) => Some(b)
      case _             => bad += (field -> "must be a boolean"); None
    }
    def strings(field: String, v: ujson.Value): List[String] = v match {
      case ujson.Arr(xs) if xs.forall(_.isInstanceOf[ujson.Str]) => xs.map(_.str).toList
      case _ => bad += (field -> "must be an array of strings"); Nil
    }

    unknown("", o, TopFields)

    // question
    val question = o.value.get("question") match {
      case Some(ujson.Str(q)) =>
        val c = QueryRequest.canonicalQuestion(q)
        if (c.isEmpty) bad += ("question" -> "must not be blank")
        else if (c.length > ServiceLimits.MaxQuestionChars)
          bad += ("question" -> s"must be at most ${ServiceLimits.MaxQuestionChars} characters")
        c
      case None => bad += ("question" -> "is required"); ""
      case _    => bad += ("question" -> "must be a string"); ""
    }

    // mode
    val mode = o.value.get("mode") match {
      case None | Some(ujson.Null) => JobMode.Sync
      case Some(ujson.Str(m))      =>
        JobMode.fromWire(m).getOrElse { bad += ("mode" -> "must be sync or async"); JobMode.Sync }
      case Some(_) => bad += ("mode" -> "must be sync or async"); JobMode.Sync
    }

    // constraints
    var windowLabel: Option[String] = None
    var topic: Option[String] = None
    objectAt("constraints").foreach { c =>
      unknown("constraints.", c, ConstraintFields)
      val from =
        c.value.get("yearFrom").filterNot(_ == ujson.Null).flatMap(int("constraints.yearFrom", _))
      val to =
        c.value.get("yearTo").filterNot(_ == ujson.Null).flatMap(int("constraints.yearTo", _))
      val fromGiven = c.value.get("yearFrom").exists(_ != ujson.Null)
      val toGiven = c.value.get("yearTo").exists(_ != ujson.Null)
      if (fromGiven != toGiven)
        bad += ("constraints" -> "yearFrom and yearTo must be given together")
      (from, to) match {
        case (Some(f), Some(t)) =>
          windows.find(w => w.startYear == f && w.endYear == t) match {
            case Some(w) => windowLabel = Some(w.label)
            case None    =>
              windowMismatch = true
              unsupported += ("constraints.yearFrom" -> "the years must equal one configured window")
          }
        case _ => ()
      }
      c.value
        .get("topics")
        .filterNot(_ == ujson.Null)
        .map(strings("constraints.topics", _))
        .foreach { ts =>
          if (ts.size > 1) unsupported += ("constraints.topics" -> "at most one topic is supported")
          ts.headOption.foreach { t =>
            val canon = QueryRequest.canonicalText(t)
            if (canon.isEmpty) bad += ("constraints.topics" -> "a topic must not be blank")
            else if (canon.exists(ch => Character.isISOControl(ch)))
              bad += ("constraints.topics" -> "a topic must not contain control characters")
            else if (canon.length > ServiceLimits.MaxTopicChars)
              unsupported += ("constraints.topics" -> s"a topic must be at most ${ServiceLimits.MaxTopicChars} characters")
            else topic = Some(canon)
          }
        }
      List("venues" -> "venue metrics", "institutions" -> "institution data").foreach {
        case (f, what) =>
          c.value.get(f).filterNot(_ == ujson.Null).map(strings(s"constraints.$f", _)).foreach {
            xs =>
              if (xs.nonEmpty) unsupported += (s"constraints.$f" -> s"must be empty (no $what yet)")
          }
      }
    }

    // output
    var includeEvidence = true
    objectAt("output").foreach { out =>
      unknown("output.", out, OutputFields)
      out.value
        .get("includeEvidence")
        .filterNot(_ == ujson.Null)
        .flatMap(bool("output.includeEvidence", _))
        .foreach(includeEvidence = _)
      List("includeScores", "includeUncertainty").foreach { f =>
        out.value.get(f).filterNot(_ == ujson.Null).flatMap(bool(s"output.$f", _)).foreach { b =>
          if (!b) unsupported += (s"output.$f" -> "must be true (always returned)")
        }
      }
    }

    // retrieval
    val retrieval = scala.collection.mutable.Map.empty[String, Int]
    objectAt("retrieval").foreach { r =>
      unknown("retrieval.", r, RetrievalFields)
      val bounds = Map(
        "graphLimit" -> runtime.graphResultLimit,
        "vectorLimit" -> vectorTopK,
        "maxPathDepth" -> runtime.maxPathDepth
      )
      RetrievalFields.foreach { f =>
        r.value.get(f).filterNot(_ == ujson.Null).flatMap(int(s"retrieval.$f", _)).foreach { n =>
          if (n < 1) bad += (s"retrieval.$f" -> "must be at least 1")
          else if (n > bounds(f))
            unsupported += (s"retrieval.$f" -> s"must be at most ${bounds(f)} in this deployment")
          else retrieval(f) = n
        }
      }
    }

    // analysis
    val analysis = scala.collection.mutable.Map.empty[String, ujson.Value]
    objectAt("analysis").foreach { a =>
      unknown("analysis.", a, AnalysisFields)
      a.value.get("rankingMetric").filterNot(_ == ujson.Null).foreach {
        case ujson.Str(m) =>
          if (PlanBounds.rankingMetrics.exists(_.wire == m))
            analysis("rankingMetric") = ujson.Str(m)
          else unsupported += ("analysis.rankingMetric" -> "must be a ranked metric wire name")
        case _ => bad += ("analysis.rankingMetric" -> "must be a string")
      }
      AnalysisFields.filterNot(_ == "rankingMetric").foreach { f =>
        a.value.get(f).filterNot(_ == ujson.Null).flatMap(bool(s"analysis.$f", _)).foreach { b =>
          analysis(f) = ujson.Bool(b)
        }
      }
    }

    if (bad.nonEmpty) Left(ApiError.invalid(bad.toList))
    else if (unsupported.nonEmpty) {
      val details = ApiError.fieldErrors(unsupported.toList)
      if (windowMismatch)
        details("windows") = ujson.Arr.from(windows.map { w =>
          ujson.Obj("label" -> w.label, "yearFrom" -> w.startYear, "yearTo" -> w.endYear)
        })
      Left(ApiError(ErrorCode.UNSUPPORTED_CONSTRAINT, details))
    } else
      Right(
        ValidatedQuery(
          question,
          windowLabel,
          topic,
          mode,
          includeEvidence,
          retrieval.toMap,
          analysis.toMap
        )
      )
  }
}

object QueryRequestParser {
  val TopFields: List[String] =
    List("question", "constraints", "mode", "output", "retrieval", "analysis")
  val ConstraintFields: List[String] =
    List("yearFrom", "yearTo", "topics", "venues", "institutions")
  val OutputFields: List[String] = List("includeEvidence", "includeScores", "includeUncertainty")
  val RetrievalFields: List[String] = List("graphLimit", "vectorLimit", "maxPathDepth")
  val AnalysisFields: List[String] = List(
    "rankingMetric",
    "includeTeamSize",
    "includeBridgeMetrics",
    "includeCommunities",
    "includePaths"
  )
}
