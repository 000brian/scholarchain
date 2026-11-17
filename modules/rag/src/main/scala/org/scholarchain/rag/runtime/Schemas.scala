package org.scholarchain.rag.runtime

import scala.util.control.NonFatal

import org.scholarchain.core.{CollaborationMetric, QueryOperation, SemanticDocumentType}
import org.scholarchain.rag.retrieval.SemanticContext

/** The shape of one JSON schema, as the hand-written validators enforce it: for every object path
  * its field names and required fields, and for every enumerated value path its allowed values.
  * Paths: `""` is the root, `a.b` a nested field, `a[]` the items of array `a`. A test checks that
  * each file under `prompts/schemas/` declares exactly this shape.
  */
final case class SchemaShape(
    schemaId: String,
    objects: Map[String, (List[String], List[String])],
    enums: Map[String, List[String]]
)

/** Small strict JSON reader with error accumulation, shared by the validators. */
private[runtime] final class JsonReader(
    path: String,
    v: ujson.Value,
    errors: collection.mutable.ListBuffer[String]
) {
  def err(msg: String): Unit = errors += (if (path.isEmpty) msg else s"$path: $msg")

  def obj(
      fields: List[String],
      required: List[String]
  ): Option[collection.Map[String, ujson.Value]] =
    v match {
      case o: ujson.Obj =>
        val unknown = o.value.keys.filterNot(fields.contains).toList.sorted
        // key names come from model output: report the count only, never the text
        if (unknown.nonEmpty)
          err(s"${unknown.size} unknown key(s); allowed: ${fields.mkString(", ")}")
        required.filterNot(o.value.contains).foreach(k => err(s"missing key '$k'"))
        if (unknown.isEmpty && required.forall(o.value.contains)) Some(o.value) else None
      case _ => err("must be an object"); None
    }
}

object JsonSupport {

  /** Whitespace around model output: Java whitespace, Unicode space separators (no-break spaces
    * included) and the byte-order mark.
    */
  def isOuterSpace(c: Char): Boolean =
    Character.isWhitespace(c) || Character.isSpaceChar(c) || c == '﻿'

  /** `s` without leading and trailing [[isOuterSpace]] characters. */
  def stripOuterSpace(s: String): String = {
    var a = 0
    var b = s.length
    while (a < b && isOuterSpace(s.charAt(a))) a += 1
    while (b > a && isOuterSpace(s.charAt(b - 1))) b -= 1
    s.substring(a, b)
  }

  /** Model output is accepted only as one JSON object, surrounded by nothing but whitespace
    * ([[isOuterSpace]]), optionally inside a single Markdown code fence (```json ... ```). Anything
    * else (prose, a second value) fails closed.
    */
  def parseObject(text: String): Either[String, ujson.Obj] = {
    val t = stripOuterSpace(Option(text).getOrElse(""))
    val unfenced =
      if (t.startsWith("```") && t.endsWith("```") && t.length >= 6) {
        val inner = t.stripPrefix("```").stripSuffix("```")
        val nl = inner.indexOf('\n')
        if (nl < 0) inner
        else {
          val tag = inner.substring(0, nl).trim
          if (tag.isEmpty || tag == "json") inner.substring(nl + 1) else inner
        }
      } else t
    try
      ujson.read(stripOuterSpace(unfenced)) match {
        case o: ujson.Obj => Right(o)
        case _            => Left("output is not a JSON object")
      }
    catch { case NonFatal(e) => Left(s"output is not valid JSON (${e.getClass.getSimpleName})") }
  }

  def str(v: ujson.Value): Option[String] = v match { case ujson.Str(s) => Some(s); case _ => None }
  def int(v: ujson.Value): Option[Int] = v match {
    case ujson.Num(n) if n.isWhole && n >= Int.MinValue && n <= Int.MaxValue => Some(n.toInt)
    case _                                                                   => None
  }
  def arr(v: ujson.Value): Option[List[ujson.Value]] = v match {
    case ujson.Arr(xs) => Some(xs.toList)
    case _             => None
  }
  def hasControl(s: String): Boolean = s.exists(c => Character.isISOControl(c) && c != '\n')
}

/** Schema `query-plan-draft-v1` (file `prompts/schemas/query-plan-draft-v1.schema.json`): the plan
  * draft the planner model (or [[RulePlanner]]) returns. Validation is an allowlist; unknown keys,
  * operations, windows and metrics are rejected.
  */
object PlanDraftSchema {
  val SchemaId = "query-plan-draft-v1"
  val Fields: List[String] = List(
    "operations",
    "researcherNames",
    "orcids",
    "windowLabel",
    "comparisonWindowLabels",
    "rankingMetric",
    "limit",
    "maxPathDepth",
    "topic",
    "unsupportedConstraints"
  )
  val ConstraintFields: List[String] = List("kind", "text")
  val OrcidPattern = "(https://orcid\\.org/)?[0-9]{4}-[0-9]{4}-[0-9]{4}-[0-9]{3}[0-9X]"

  val shape: SchemaShape = SchemaShape(
    SchemaId,
    Map("" -> (Fields, Fields), "unsupportedConstraints[]" -> (ConstraintFields, ConstraintFields)),
    Map(
      "operations[]" -> QueryOperation.values.map(_.wire),
      "rankingMetric" -> PlanBounds.rankingMetrics.map(_.wire),
      "unsupportedConstraints[].kind" -> UnsupportedConstraint.Kinds
    )
  )

  def validate(text: String, b: PlanBounds): Either[String, PlanDraft] =
    JsonSupport.parseObject(text).flatMap(validateJson(_, b))

  def validateJson(root: ujson.Value, b: PlanBounds): Either[String, PlanDraft] = {
    import JsonSupport._
    val errors = collection.mutable.ListBuffer.empty[String]
    def e(m: String): Unit = errors += m
    val fields = new JsonReader("", root, errors).obj(Fields, Fields)
    val draft = fields.map { o =>
      def strList(k: String, max: Int, maxChars: Int): List[String] = arr(o(k)) match {
        case None     => e(s"$k: must be an array"); Nil
        case Some(xs) =>
          if (xs.size > max) e(s"$k: at most $max entries")
          xs.zipWithIndex.flatMap { case (x, i) =>
            str(x) match {
              case Some(s) if s.trim.isEmpty      => e(s"$k[$i]: must not be blank"); None
              case Some(s) if s.length > maxChars =>
                e(s"$k[$i]: at most $maxChars characters"); None
              case Some(s) if hasControl(s) => e(s"$k[$i]: control characters"); None
              case Some(s)                  => Some(s.trim)
              case None                     => e(s"$k[$i]: must be a string"); None
            }
          }
      }
      def optStr(k: String, maxChars: Int): Option[String] = o(k) match {
        case ujson.Null                          => None
        case ujson.Str(s) if s.trim.isEmpty      => e(s"$k: must not be blank (use null)"); None
        case ujson.Str(s) if s.length > maxChars => e(s"$k: at most $maxChars characters"); None
        case ujson.Str(s) if hasControl(s)       => e(s"$k: control characters"); None
        case ujson.Str(s)                        => Some(s.trim)
        case _                                   => e(s"$k: must be a string or null"); None
      }
      def optInt(k: String, lo: Int, hi: Int): Option[Int] = o(k) match {
        case ujson.Null => None
        case v          =>
          int(v) match {
            case Some(n) if n >= lo && n <= hi => Some(n)
            case Some(n)                       => e(s"$k: $n is outside $lo..$hi"); None
            case None                          => e(s"$k: must be an integer or null"); None
          }
      }
      def window(k: String, s: String): Option[String] =
        if (b.windowLabels.contains(s)) Some(s)
        else { e(s"$k: not a configured window (${b.windowLabels.mkString(", ")})"); None }

      val ops = arr(o("operations")) match {
        case None     => e("operations: must be an array"); Nil
        case Some(xs) =>
          if (xs.isEmpty) e("operations: at least one operation")
          if (xs.size > PlanBounds.MaxOperations)
            e(s"operations: at most ${PlanBounds.MaxOperations}")
          val parsed = xs.zipWithIndex.flatMap { case (x, i) =>
            str(x).flatMap(s => QueryOperation.fromWire(s).toOption) match {
              case Some(op) => Some(op)
              case None     =>
                e(
                  s"operations[$i]: not an allowed operation (${QueryOperation.values.map(_.wire).mkString(", ")})"
                );
                None
            }
          }
          if (parsed.distinct.size != parsed.size) e("operations: duplicates")
          parsed
      }
      val names = strList("researcherNames", b.maxResearcherNames, PlanBounds.MaxNameChars)
      val orcids = strList("orcids", b.maxResearcherNames, 40)
      orcids.zipWithIndex.foreach { case (s, i) =>
        if (!s.matches(OrcidPattern)) e(s"orcids[$i]: not an ORCID iD")
      }
      val w = optStr("windowLabel", 40).flatMap(window("windowLabel", _))
      val cmp = strList("comparisonWindowLabels", b.windowLabels.size, 40)
        .flatMap(window("comparisonWindowLabels", _))
      if (cmp.distinct.size != cmp.size) e("comparisonWindowLabels: duplicates")
      val metric = optStr("rankingMetric", 60).flatMap { s =>
        PlanBounds.rankingMetrics.find(_.wire == s) match {
          case Some(m) => Some(m)
          case None    => e("rankingMetric: not a ranked metric"); None
        }
      }
      val limit = optInt("limit", 1, b.rankingTopK)
      val depth = optInt("maxPathDepth", 1, b.maxPathDepth)
      val topic = optStr("topic", PlanBounds.MaxTopicChars)
      val constraints = arr(o("unsupportedConstraints")) match {
        case None     => e("unsupportedConstraints: must be an array"); Nil
        case Some(xs) =>
          if (xs.size > PlanBounds.MaxUnsupported)
            e(s"unsupportedConstraints: at most ${PlanBounds.MaxUnsupported}")
          xs.zipWithIndex.flatMap { case (x, i) =>
            new JsonReader(s"unsupportedConstraints[$i]", x, errors)
              .obj(ConstraintFields, ConstraintFields)
              .flatMap { c =>
                (str(c("kind")), str(c("text"))) match {
                  case (Some(k), Some(t))
                      if UnsupportedConstraint.Kinds.contains(k) && t.trim.nonEmpty &&
                        t.length <= UnsupportedConstraint.MaxTextChars && !hasControl(t) =>
                    Some(UnsupportedConstraint(k, t.trim))
                  case _ =>
                    e(
                      s"unsupportedConstraints[$i]: kind must be one of ${UnsupportedConstraint.Kinds.mkString(", ")} and text 1..${UnsupportedConstraint.MaxTextChars} characters"
                    );
                    None
                }
              }
          }
      }
      PlanDraft(ops, names, orcids, w, cmp, metric, limit, depth, topic, constraints)
    }
    if (errors.isEmpty && draft.isDefined) Right(draft.get) else Left(errors.mkString("; "))
  }
}

/** One number a claim uses, with the evidence item and field it came from. */
final case class DraftNumber(value: Double, evidenceId: String, field: String)

/** One claim of the synthesis output. */
final case class DraftClaim(
    claimId: String,
    text: String,
    evidenceIds: List[String],
    numbers: List[DraftNumber],
    uncertainty: Option[String]
)

/** The synthesis output (schema `answer-claims-v1`). */
final case class AnswerDraft(
    summary: String,
    claims: List[DraftClaim],
    missingEvidence: List[String]
) {
  def toJson: ujson.Obj = ujson.Obj(
    "summary" -> summary,
    "claims" -> ujson.Arr.from(claims.map { c =>
      ujson.Obj(
        "claimId" -> c.claimId,
        "text" -> c.text,
        "evidenceIds" -> ujson.Arr.from(c.evidenceIds.map(ujson.Str(_))),
        "numbers" -> ujson.Arr.from(
          c.numbers.map(n =>
            ujson.Obj("value" -> n.value, "evidenceId" -> n.evidenceId, "field" -> n.field)
          )
        ),
        "uncertainty" -> c.uncertainty.map(ujson.Str(_)).getOrElse(ujson.Null)
      )
    }),
    "missingEvidence" -> ujson.Arr.from(missingEvidence.map(ujson.Str(_)))
  )
}

/** The limits of one version of the answer-claims schema. `numbersInText`: every entry of a claim's
  * `numbers` must appear as a number literal in that claim's text (v2, v3; formats of
  * [[NumberFormats]]). `dropUnmatchedNumbers` (v3): an entry whose value does not appear is dropped
  * from `numbers` and counted by its [[NumberFormats.failureClass]], instead of rejecting the whole
  * output; the verifier still checks every number of the text. `retryHints` (v3): a structured
  * retry adds the fixed number-format hint, and asks for at most
  * [[AnswerSynthesis.TruncatedMaxClaims]] claims after an output cut off at the output token limit
  * (U-10).
  */
final case class AnswerClaimsSpec(
    schemaId: String,
    maxClaims: Int,
    maxTextChars: Int,
    maxNumbersPerClaim: Int,
    numbersInText: Boolean,
    dropUnmatchedNumbers: Boolean = false,
    retryHints: Boolean = false
)

object AnswerClaimsSpec {

  /** `answer-claims-v1` (Phase 9). */
  val V1: AnswerClaimsSpec =
    AnswerClaimsSpec("answer-claims-v1", 40, 600, 20, numbersInText = false)

  /** `answer-claims-v2` (Phase 10, decision 5b): smaller output, so it fits the output cap. */
  val V2: AnswerClaimsSpec = AnswerClaimsSpec("answer-claims-v2", 15, 300, 10, numbersInText = true)

  /** `answer-claims-v3` (Phase 11, L-1): the v2 limits; a bound number that is not in its claim
    * text is dropped and counted, not a rejection; retries carry fixed hints.
    */
  val V3: AnswerClaimsSpec = AnswerClaimsSpec(
    "answer-claims-v3",
    15,
    300,
    10,
    numbersInText = true,
    dropUnmatchedNumbers = true,
    retryHints = true
  )

  val all: List[AnswerClaimsSpec] = List(V1, V2, V3)
}

/** The validation of one answer-claims output with its bound-number accounting (never model text):
  * `droppedNumbers` are the failure classes of the bound numbers v3 dropped; `numberErrors` are the
  * failure classes of the bound numbers that made v2 reject the output.
  */
final case class AnswerCheck(
    result: Either[String, AnswerDraft],
    droppedNumbers: List[String],
    numberErrors: List[String]
)

/** Schemas `answer-claims-v1`, `-v2` and `-v3` (files
  * `prompts/schemas/answer-claims-v{1,2,3}.schema.json`; same fields, different limits and
  * bound-number rules, see [[AnswerClaimsSpec]]). The constants and the one-argument methods are
  * v1.
  */
object AnswerClaimsSchema {
  val SchemaId: String = AnswerClaimsSpec.V1.schemaId
  val Fields: List[String] = List("summary", "claims", "missingEvidence")
  val ClaimFields: List[String] = List("claimId", "text", "evidenceIds", "numbers", "uncertainty")
  val NumberFields: List[String] = List("value", "evidenceId", "field")
  val EvidenceIdPattern = "evidence:v1:[0-9a-f]{32}"
  val ClaimIdPattern = "claim-[0-9]{1,4}"
  val MaxClaims: Int = AnswerClaimsSpec.V1.maxClaims
  val MaxTextChars: Int = AnswerClaimsSpec.V1.maxTextChars
  val MaxSummaryChars = 600
  val MaxEvidencePerClaim = 20
  val MaxNumbersPerClaim: Int = AnswerClaimsSpec.V1.maxNumbersPerClaim
  val MaxUncertaintyChars = 300
  val MaxMissing = 10

  def shapeOf(spec: AnswerClaimsSpec): SchemaShape = SchemaShape(
    spec.schemaId,
    Map(
      "" -> (Fields, Fields),
      "claims[]" -> (ClaimFields, ClaimFields),
      "claims[].numbers[]" -> (NumberFields, NumberFields)
    ),
    Map("claims[].numbers[].field" -> EvidenceValues.Fields)
  )

  val shape: SchemaShape = shapeOf(AnswerClaimsSpec.V1)

  /** True when `value` appears in `text` as an accepted number literal (the formats of
    * [[NumberFormats]], shared with the verifier; digits inside identifiers do not count, L-4).
    */
  def appearsInText(value: Double, text: String): Boolean =
    NumberFormats.appearsInText(value, text)

  def validate(text: String): Either[String, AnswerDraft] = validate(text, AnswerClaimsSpec.V1)

  def validate(text: String, spec: AnswerClaimsSpec): Either[String, AnswerDraft] =
    check(text, spec).result

  /** [[validate]] with the bound-number accounting of [[AnswerCheck]]. */
  def check(text: String, spec: AnswerClaimsSpec): AnswerCheck =
    JsonSupport.parseObject(text) match {
      case Left(e)  => AnswerCheck(Left(e), Nil, Nil)
      case Right(o) => checkJson(o, spec)
    }

  def validateJson(root: ujson.Value): Either[String, AnswerDraft] =
    validateJson(root, AnswerClaimsSpec.V1)

  def validateJson(root: ujson.Value, spec: AnswerClaimsSpec): Either[String, AnswerDraft] =
    checkJson(root, spec).result

  def checkJson(root: ujson.Value, spec: AnswerClaimsSpec): AnswerCheck = {
    val MaxClaims = spec.maxClaims
    val MaxTextChars = spec.maxTextChars
    val MaxNumbersPerClaim = spec.maxNumbersPerClaim
    import JsonSupport._
    val dropped = collection.mutable.ListBuffer.empty[String]
    val numberErrors = collection.mutable.ListBuffer.empty[String]
    val errors = collection.mutable.ListBuffer.empty[String]
    def e(m: String): Unit = errors += m
    def text(k: String, v: ujson.Value, max: Int): Option[String] = str(v) match {
      case Some(s) if s.trim.isEmpty => e(s"$k: must not be blank"); None
      case Some(s) if s.length > max => e(s"$k: at most $max characters"); None
      case Some(s) if hasControl(s)  => e(s"$k: control characters"); None
      case Some(s)                   => Some(s.trim)
      case None                      => e(s"$k: must be a string"); None
    }
    val draft = new JsonReader("", root, errors).obj(Fields, Fields).map { o =>
      val summary = text("summary", o("summary"), MaxSummaryChars).getOrElse("")
      val claims = arr(o("claims")) match {
        case None     => e("claims: must be an array"); Nil
        case Some(xs) =>
          if (xs.size > MaxClaims) e(s"claims: at most $MaxClaims")
          xs.zipWithIndex.flatMap { case (x, i) =>
            val p = s"claims[$i]"
            new JsonReader(p, x, errors).obj(ClaimFields, ClaimFields).map { c =>
              val id = str(c("claimId")).filter(_.matches(ClaimIdPattern)).getOrElse {
                e(s"$p.claimId: must match $ClaimIdPattern"); ""
              }
              val t = text(s"$p.text", c("text"), MaxTextChars).getOrElse("")
              val ids = arr(c("evidenceIds")) match {
                case None     => e(s"$p.evidenceIds: must be an array"); Nil
                case Some(ys) =>
                  if (ys.size > MaxEvidencePerClaim)
                    e(s"$p.evidenceIds: at most $MaxEvidencePerClaim")
                  ys.zipWithIndex.flatMap { case (y, j) =>
                    str(y).filter(_.matches(EvidenceIdPattern)) match {
                      case s @ Some(_) => s
                      case None => e(s"$p.evidenceIds[$j]: must match $EvidenceIdPattern"); None
                    }
                  }
              }
              if (ids.distinct.size != ids.size) e(s"$p.evidenceIds: duplicates")
              val nums = arr(c("numbers")) match {
                case None     => e(s"$p.numbers: must be an array"); Nil
                case Some(ys) =>
                  if (ys.size > MaxNumbersPerClaim) e(s"$p.numbers: at most $MaxNumbersPerClaim")
                  ys.zipWithIndex.flatMap { case (y, j) =>
                    val q = s"$p.numbers[$j]"
                    new JsonReader(q, y, errors).obj(NumberFields, NumberFields).flatMap { n =>
                      val value = n("value") match {
                        case ujson.Num(d) if !d.isNaN && !d.isInfinite => Some(d)
                        case _ => e(s"$q.value: must be a finite number"); None
                      }
                      val ev = str(n("evidenceId")).filter(_.matches(EvidenceIdPattern))
                      if (ev.isEmpty) e(s"$q.evidenceId: must match $EvidenceIdPattern")
                      val f = str(n("field")).filter(EvidenceValues.Fields.contains)
                      if (f.isEmpty)
                        e(s"$q.field: must be one of ${EvidenceValues.Fields.mkString(", ")}")
                      for { v <- value; i <- ev; fl <- f } yield DraftNumber(v, i, fl)
                    }
                  }
              }
              val kept =
                if (!spec.numbersInText || t.isEmpty) nums
                else
                  nums.zipWithIndex.flatMap { case (n, j) =>
                    if (appearsInText(n.value, t)) List(n)
                    else if (spec.dropUnmatchedNumbers) {
                      // v3: dropped and counted by a fixed class; the text is never kept
                      dropped += NumberFormats.failureClass(n.value, t); Nil
                    } else {
                      // the value is model output: the error names the position only
                      numberErrors += NumberFormats.failureClass(n.value, t)
                      e(s"$p.numbers[$j]: the value does not appear in the claim text"); List(n)
                    }
                  }
              val unc = c("uncertainty") match {
                case ujson.Null => None
                case v          => text(s"$p.uncertainty", v, MaxUncertaintyChars)
              }
              DraftClaim(id, t, ids, kept, unc)
            }
          }
      }
      val ids = claims.map(_.claimId).filter(_.nonEmpty)
      if (ids.distinct.size != ids.size) e("claims: claim IDs must be unique")
      val missing = arr(o("missingEvidence")) match {
        case None     => e("missingEvidence: must be an array"); Nil
        case Some(xs) =>
          if (xs.size > MaxMissing) e(s"missingEvidence: at most $MaxMissing")
          xs.zipWithIndex.flatMap { case (x, i) =>
            text(s"missingEvidence[$i]", x, MaxUncertaintyChars)
          }
      }
      AnswerDraft(summary, claims, missing)
    }
    AnswerCheck(
      if (errors.isEmpty && draft.isDefined) Right(draft.get) else Left(errors.mkString("; ")),
      dropped.toList,
      numberErrors.toList
    )
  }
}

/** Schema `semantic-context-v1` (file `prompts/schemas/semantic-context-v1.schema.json`): the
  * compact semantic-context block of [[SemanticContext]] (format
  * `scholarchain.rag.semantic-context` v1). The SemanticContext stage validates every retrieval
  * result against it before use.
  */
object SemanticContextSchema {
  val SchemaId = "semantic-context-v1"
  val Fields: List[String] = List("format", "formatVersion", "usage", "query", "entries")
  val UsageFields: List[String] = List("role", "neverASourceOf", "graphFactsAllowed", "statement")
  val QueryFields: List[String] = List("index", "queryEmbeddingModel", "topK", "filterKeys")
  val WindowFields: List[String] = List("startYear", "endYear")

  val shape: SchemaShape = SchemaShape(
    SchemaId,
    Map(
      "" -> (Fields, Fields),
      "usage" -> (UsageFields, UsageFields),
      "query" -> (QueryFields, QueryFields),
      "entries[]" -> (SemanticContext.EntryFields, SemanticContext.EntryFields),
      "entries[].window" -> (WindowFields, WindowFields)
    ),
    Map(
      "format" -> List(SemanticContext.Format),
      "usage.role" -> List(SemanticContext.UsagePolicy.role),
      "usage.neverASourceOf[]" -> SemanticContext.NeverASourceOf,
      "entries[].documentType" -> SemanticDocumentType.values.map(_.wire)
    )
  )

  def validate(v: ujson.Value): Either[String, Unit] = {
    import JsonSupport._
    val errors = collection.mutable.ListBuffer.empty[String]
    def e(m: String): Unit = errors += m
    new JsonReader("", v, errors).obj(Fields, Fields).foreach { o =>
      if (str(o("format")).forall(_ != SemanticContext.Format)) e("format: wrong value")
      if (int(o("formatVersion")).forall(_ != SemanticContext.FormatVersion))
        e("formatVersion: must be 1")
      new JsonReader("usage", o("usage"), errors).obj(UsageFields, UsageFields).foreach { u =>
        if (str(u("role")).forall(_ != SemanticContext.UsagePolicy.role))
          e("usage.role: wrong value")
        if (u("graphFactsAllowed") != ujson.False) e("usage.graphFactsAllowed: must be false")
        if (
          arr(u("neverASourceOf"))
            .map(_.flatMap(str))
            .forall(_.toSet != SemanticContext.NeverASourceOf.toSet)
        )
          e("usage.neverASourceOf: wrong values")
      }
      new JsonReader("query", o("query"), errors).obj(QueryFields, QueryFields).foreach { q =>
        if (int(q("topK")).forall(_ < 1)) e("query.topK: must be a positive integer")
        if (arr(q("filterKeys")).forall(_.exists(str(_).isEmpty))) e("query.filterKeys: strings")
      }
      arr(o("entries")) match {
        case None     => e("entries: must be an array")
        case Some(xs) =>
          xs.zipWithIndex.foreach { case (x, i) =>
            val p = s"entries[$i]"
            new JsonReader(p, x, errors)
              .obj(SemanticContext.EntryFields, SemanticContext.EntryFields)
              .foreach { en =>
                if (str(en("documentType")).forall(t => SemanticDocumentType.fromWire(t).isLeft))
                  e(s"$p.documentType: unknown")
                if (str(en("textHash")).forall(!_.matches("sha256:[0-9a-f]{64}")))
                  e(s"$p.textHash: malformed")
                if (str(en("textS3Uri")).forall(!_.startsWith("s3://")))
                  e(s"$p.textS3Uri: malformed")
                en("score") match {
                  case ujson.Num(d) if !d.isNaN && !d.isInfinite => ()
                  case _                                         => e(s"$p.score: must be a number")
                }
                en("window") match {
                  case ujson.Null => ()
                  case w          =>
                    new JsonReader(s"$p.window", w, errors)
                      .obj(WindowFields, WindowFields)
                      .foreach { ww =>
                        if (int(ww("startYear")).isEmpty || int(ww("endYear")).isEmpty)
                          e(s"$p.window: integers")
                      }
                }
              }
          }
      }
    }
    if (errors.isEmpty) Right(()) else Left(errors.mkString("; "))
  }
}

/** Checks that a schema file declares exactly a [[SchemaShape]] (used by tests and at prompt
  * loading). Reads `properties`, `required`, `items` and `enum` (null entries ignored).
  */
object SchemaFiles {
  def shapeOf(
      schema: ujson.Value
  ): (Map[String, (List[String], List[String])], Map[String, List[String]]) = {
    val objects = collection.mutable.LinkedHashMap.empty[String, (List[String], List[String])]
    val enums = collection.mutable.LinkedHashMap.empty[String, List[String]]
    def walk(path: String, node: ujson.Value): Unit = node match {
      case o: ujson.Obj =>
        o.value.get("properties").foreach {
          case props: ujson.Obj =>
            val req = o.value
              .get("required")
              .flatMap(JsonSupport.arr)
              .getOrElse(Nil)
              .flatMap(JsonSupport.str)
            objects(path) = (props.value.keys.toList, req)
            props.value.foreach { case (k, v) => walk(if (path.isEmpty) k else s"$path.$k", v) }
          case _ => ()
        }
        o.value.get("items").foreach(walk(s"$path[]", _))
        o.value
          .get("enum")
          .flatMap(JsonSupport.arr)
          .foreach(xs => enums(path) = xs.flatMap(JsonSupport.str))
        o.value.get("anyOf").flatMap(JsonSupport.arr).foreach(_.foreach(walk(path, _)))
      case _ => ()
    }
    walk("", schema)
    (objects.toMap, enums.toMap)
  }

  /** Differences between a schema file and the validator's shape (empty when they agree). */
  def differences(schema: ujson.Value, shape: SchemaShape): List[String] = {
    val (objs, ens) = shapeOf(schema)
    val idErr =
      if (schema.obj.get("$id").flatMap(JsonSupport.str).exists(_.contains(shape.schemaId))) Nil
      else List(s"$$id does not name ${shape.schemaId}")
    val objErr = (objs.keySet ++ shape.objects.keySet).toList.sorted.flatMap { p =>
      (objs.get(p), shape.objects.get(p)) match {
        case (Some((f1, r1)), Some((f2, r2))) =>
          (if (f1.toSet != f2.toSet) List(s"'$p' fields: file ${f1.sorted} vs scala ${f2.sorted}")
           else Nil) ++
            (if (r1.toSet != r2.toSet)
               List(s"'$p' required: file ${r1.sorted} vs scala ${r2.sorted}")
             else Nil)
        case (None, _) => List(s"'$p' object missing in the file")
        case (_, None) => List(s"'$p' object missing in the validator")
      }
    }
    val enErr = (ens.keySet ++ shape.enums.keySet).toList.sorted.flatMap { p =>
      (ens.get(p), shape.enums.get(p)) match {
        case (Some(a), Some(b)) if a.toSet == b.toSet => Nil
        case (a, b) => List(s"'$p' enum: file ${a.map(_.sorted)} vs scala ${b.map(_.sorted)}")
      }
    }
    idErr ++ objErr ++ enErr
  }
}

/** Ranked metrics as plain wire names (for prompts). */
object MetricNames {
  def ranked: List[String] = PlanBounds.rankingMetrics.map(_.wire)
  def phraseOf(wire: String): String =
    CollaborationMetric.fromWire(wire).map(Fact.phrase).getOrElse(wire)
}
