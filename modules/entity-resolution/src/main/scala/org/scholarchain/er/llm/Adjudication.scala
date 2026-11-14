package org.scholarchain.er.llm

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.collection.mutable

import org.scholarchain.core.{
  AdjudicationOutcome,
  CodePointOrder,
  ContentHash,
  Contract,
  LlmAdjudication,
  LlmAdjudicationId,
  MatchCandidateId,
  Provenance,
  SchemaVersions
}
import org.scholarchain.er.io.{DataFiles, ErError}

/** The versioned adjudication prompt (`src/main/resources/er/llm/adjudication-prompt-v1.txt`). The
  * file starts with a header (`prompt-version:`, `output-schema:`, `---`); the body holds one
  * `{{INPUT_JSON}}` placeholder.
  */
final case class AdjudicationPrompt(
    version: String,
    outputSchemaVersion: String,
    body: String,
    sha256: String
) {
  def render(inputJson: String): String = body.replace(AdjudicationPrompt.Placeholder, inputJson)
}

object AdjudicationPrompt {
  val Resource = "er/llm/adjudication-prompt-v1.txt"
  val SchemaResource = "er/llm/adjudication-output-schema-v1.json"
  val Version = "er-adjudication-prompt/1"
  val OutputSchemaVersion = "er-adjudication-output/1"
  val Placeholder = "{{INPUT_JSON}}"

  lazy val v1: AdjudicationPrompt = {
    val bytes = DataFiles.readResource(Resource)
    val text = new String(bytes, StandardCharsets.UTF_8)
    val (header, body) = text.split("\n---\n", 2) match {
      case Array(h, b) => (h, b)
      case _           => throw new ErError(s"$Resource: missing header separator")
    }
    val fields = header.linesIterator
      .map(_.split(":", 2))
      .collect { case Array(k, v) => k.trim -> v.trim }
      .toMap
    val p = AdjudicationPrompt(
      fields.getOrElse("prompt-version", ""),
      fields.getOrElse("output-schema", ""),
      body,
      DataFiles.sha256OfBytes(bytes)
    )
    if (p.version != Version)
      throw new ErError(s"$Resource: prompt-version ${p.version} != $Version")
    if (p.outputSchemaVersion != OutputSchemaVersion)
      throw new ErError(
        s"$Resource: output-schema ${p.outputSchemaVersion} != $OutputSchemaVersion"
      )
    if (body.split(java.util.regex.Pattern.quote(Placeholder), -1).length != 2)
      throw new ErError(s"$Resource: the body must hold exactly one $Placeholder")
    p
  }

  lazy val schemaSha256: String = DataFiles.sha256OfBytes(DataFiles.readResource(SchemaResource))
}

/** One adjudication request: the canonical input JSON, its content hash and the rendered prompt.
  * The adjudicator sees copies of source facts; nothing it returns is written back to a source
  * record.
  */
final case class AdjudicationRequest(
    inputJson: String,
    inputHash: String,
    prompt: String,
    promptVersion: String,
    offeredCandidateIds: List[MatchCandidateId],
    citablePaths: Set[String],
    inputKeys: Set[String]
)

object AdjudicationRequest {

  /** Builds a request from the input object (keys in a fixed order). */
  def of(
      input: ujson.Obj,
      offered: List[MatchCandidateId],
      prompt: AdjudicationPrompt
  ): AdjudicationRequest = {
    val json = input.render()
    val paths = mutable.Set.empty[String]
    val keys = mutable.Set.empty[String]
    def walk(prefix: String, v: ujson.Value): Unit = v match {
      case o: ujson.Obj =>
        if (prefix.nonEmpty) paths += prefix
        o.value.foreach { case (k, x) =>
          keys += k
          walk(if (prefix.isEmpty) k else s"$prefix.$k", x)
        }
      case a: ujson.Arr =>
        paths += prefix
        a.value.indices.foreach(i => paths += s"$prefix[$i]")
      case _ => paths += prefix
    }
    walk("", input)
    AdjudicationRequest(
      json,
      ContentHash.of(json),
      prompt.render(json),
      prompt.version,
      offered,
      paths.toSet,
      keys.toSet
    )
  }
}

/** The adjudicator port (decision 12). An implementation returns the model's raw reply text; the
  * caller validates it. The model ID comes from configuration (`modelVersion`); nothing here names
  * a model. The live Bedrock adapter is added later (Phase 9); this module has no AWS dependency.
  */
trait Adjudicator {
  def modelVersion: String
  def adjudicate(request: AdjudicationRequest): String
}

/** The adjudicator of the Phase 5 real run: always abstains, so every LLM-band pair it sees ends
  * unresolved with reason `llm-abstain`.
  */
final class AlwaysAbstainAdjudicator(val modelVersion: String) extends Adjudicator {
  require(modelVersion != null && modelVersion.trim.nonEmpty, "model version must not be blank")
  def adjudicate(request: AdjudicationRequest): String =
    """{"outcome":"abstain","chosenCandidateId":null,"citedInputFields":[],"confidence":0,"reasonCodes":["always-abstain"]}"""
}

/** Strict JSON parsing: like `ujson.read`, but a reply in which any object (at any nesting level)
  * repeats a key is refused instead of resolved last-wins. A model reply with a repeated key is
  * ambiguous (`{"outcome":"abstain",...,"outcome":"accept"}`), so no reading of it is trusted.
  */
object StrictJson {

  /** Why a text was refused. */
  sealed trait Failure
  final case class DuplicateKey(key: String) extends Failure
  case object Malformed extends Failure

  private final class Found(val key: String) extends RuntimeException(key, null, false, false)

  private final class Checking[T, J](inner: upickle.core.Visitor[T, J])
      extends upickle.core.Visitor.Delegate[T, J](inner) {

    override def visitObject(
        length: Int,
        jsonableKeys: Boolean,
        index: Int
    ): upickle.core.ObjVisitor[T, J] = {
      val obj = inner.visitObject(length, jsonableKeys, index)
      new upickle.core.ObjVisitor[T, J] {
        private val seen = mutable.HashSet.empty[String]
        def visitKey(index: Int): upickle.core.Visitor[?, ?] = obj.visitKey(index)
        def visitKeyValue(v: Any): Unit = {
          val k = String.valueOf(v)
          if (!seen.add(k)) throw new Found(k)
          obj.visitKeyValue(v)
        }
        def subVisitor: upickle.core.Visitor[?, ?] =
          new Checking(obj.subVisitor.asInstanceOf[upickle.core.Visitor[Any, Any]])
        def visitValue(v: T, index: Int): Unit = obj.visitValue(v, index)
        def visitEnd(index: Int): J = obj.visitEnd(index)
      }
    }

    override def visitArray(length: Int, index: Int): upickle.core.ArrVisitor[T, J] = {
      val arr = inner.visitArray(length, index)
      new upickle.core.ArrVisitor[T, J] {
        def subVisitor: upickle.core.Visitor[?, ?] =
          new Checking(arr.subVisitor.asInstanceOf[upickle.core.Visitor[Any, Any]])
        def visitValue(v: T, index: Int): Unit = arr.visitValue(v, index)
        def visitEnd(index: Int): J = arr.visitEnd(index)
      }
    }
  }

  private def found(e: Throwable): Option[String] =
    Iterator
      .iterate(e)(_.getCause)
      .takeWhile(_ != null)
      .take(16)
      .collectFirst { case f: Found => f.key }

  /** Parses `text` into a [[ujson.Value]], refusing malformed JSON and repeated keys. */
  def read(text: String): Either[Failure, ujson.Value] =
    if (text == null) Left(Malformed)
    else
      try Right(ujson.transform(ujson.Readable.fromString(text), new Checking(ujson.Value)))
      catch {
        case scala.util.control.NonFatal(e) =>
          Left(found(e).fold[Failure](Malformed)(DuplicateKey(_)))
      }
}

/** Strict validation of a model reply against `er-adjudication-output/1` plus the request. Every
  * failure has a reason token used in `llm-output-rejected:<reason>`:
  *   - `malformed-json`: not one JSON object;
  *   - `duplicate-key`: some object of the reply (at any nesting level) repeats a key; the reply is
  *     ambiguous and is never resolved last-wins;
  *   - `source-field-mutation`: a key names an input field (an attempt to change source data);
  *   - `schema`: unknown key, missing key, or wrong type or value;
  *   - `unoffered-candidate`: chosenCandidateId is not one of the offered candidate IDs;
  *   - `unknown-cited-field`: a cited path does not exist in the input;
  *   - `contract`: the resulting [[LlmAdjudication]] fails the core contract.
  */
object OutputValidator {
  val Keys: Set[String] =
    Set("outcome", "chosenCandidateId", "citedInputFields", "confidence", "reasonCodes")
  private val CodeToken = "[A-Za-z0-9][A-Za-z0-9._:/-]*"
  private val FieldToken = """[A-Za-z0-9][A-Za-z0-9._:/\[\]-]*"""

  final case class Rejected(reason: String, detail: String)

  def validate(
      raw: String,
      request: AdjudicationRequest,
      modelVersion: String,
      policyVersion: String,
      provenance: Provenance
  ): Either[Rejected, LlmAdjudication] = {
    def fail(reason: String, detail: String) = Left(Rejected(reason, detail))
    StrictJson.read(raw) match {
      case Left(StrictJson.DuplicateKey(k)) => fail("duplicate-key", k.take(64))
      case Right(o: ujson.Obj)              =>
        val keys = o.value.keySet.toSet
        val mutation = keys.intersect(request.inputKeys) -- Keys
        if (mutation.nonEmpty)
          return fail("source-field-mutation", mutation.toList.sorted.mkString(","))
        val unknown = keys -- Keys
        if (unknown.nonEmpty)
          return fail("schema", s"unknown keys ${unknown.toList.sorted.mkString(",")}")
        val missing = Keys -- keys
        if (missing.nonEmpty)
          return fail("schema", s"missing keys ${missing.toList.sorted.mkString(",")}")
        val outcome = o("outcome") match {
          case ujson.Str(s) => AdjudicationOutcome.fromWire(s).toOption
          case _            => None
        }
        if (outcome.isEmpty) return fail("schema", "outcome")
        def strings(k: String): Option[List[String]] = o(k) match {
          case a: ujson.Arr if a.value.forall(_.isInstanceOf[ujson.Str]) =>
            Some(a.value.toList.map(_.str))
          case _ => None
        }
        val cited = strings("citedInputFields")
        val reasons = strings("reasonCodes")
        if (
          cited.isEmpty || cited.get.distinct.size != cited.get.size || cited.get.size > 32 || cited.get
            .exists(!_.matches(FieldToken))
        )
          return fail("schema", "citedInputFields")
        if (
          reasons.isEmpty || reasons.get.distinct.size != reasons.get.size || reasons.get.size > 16 || reasons.get
            .exists(!_.matches(CodeToken))
        )
          return fail("schema", "reasonCodes")
        val confidence = o("confidence") match {
          case ujson.Num(d) if d >= 0.0 && d <= 1.0 => Some(d)
          case _                                    => None
        }
        if (confidence.isEmpty) return fail("schema", "confidence")
        val chosen: Either[Rejected, Option[MatchCandidateId]] = o("chosenCandidateId") match {
          case ujson.Null   => Right(None)
          case ujson.Str(s) =>
            MatchCandidateId.parse(s) match {
              case Left(_) => fail("unoffered-candidate", s)
              case Right(id) if !request.offeredCandidateIds.contains(id) =>
                fail("unoffered-candidate", s)
              case Right(id) => Right(Some(id))
            }
          case _ => fail("schema", "chosenCandidateId")
        }
        chosen.flatMap { c =>
          val badCite = cited.get.filterNot(p => request.citablePaths.contains(p))
          if (badCite.nonEmpty) fail("unknown-cited-field", badCite.sorted.mkString(","))
          else if (outcome.get == AdjudicationOutcome.Accept && c.isEmpty)
            fail("schema", "accept without chosenCandidateId")
          else if (outcome.get != AdjudicationOutcome.Accept && c.nonEmpty)
            fail("schema", s"${outcome.get.wire} with chosenCandidateId")
          else
            LlmAdjudicationId.of(
              request.inputHash,
              request.promptVersion,
              modelVersion,
              policyVersion
            ) match {
              case Left(e)   => fail("contract", e)
              case Right(id) =>
                val adj = LlmAdjudication(
                  SchemaVersions.currentOf("LlmAdjudication"),
                  id,
                  request.inputHash,
                  request.offeredCandidateIds
                    .sortWith((x, y) => CodePointOrder.lessThan(x.value, y.value)),
                  outcome.get,
                  c,
                  cited.get.sorted(CodePointOrder.ordering),
                  confidence.get,
                  reasons.get.sorted(CodePointOrder.ordering),
                  request.promptVersion,
                  modelVersion,
                  policyVersion,
                  provenance
                    .copy(contentHash = Some(request.inputHash), policyVersion = policyVersion)
                )
                Contract.validate(adj) match {
                  case Nil    => Right(adj)
                  case errors => fail("contract", errors.mkString("; "))
                }
            }
        }
      case _ => fail("malformed-json", "reply is not one JSON object")
    }
  }
}

/** Adjudication cache keyed by [[LlmAdjudicationId]] = (input hash, prompt version, model version,
  * policy version). Changing any of the four is a miss.
  */
final class AdjudicationCache {
  private val entries = mutable.HashMap.empty[LlmAdjudicationId, LlmAdjudication]
  private val added = mutable.ArrayBuffer.empty[LlmAdjudication]
  var hits = 0L
  var misses = 0L

  def get(
      inputHash: String,
      promptVersion: String,
      modelVersion: String,
      policyVersion: String
  ): Option[LlmAdjudication] =
    LlmAdjudicationId
      .of(inputHash, promptVersion, modelVersion, policyVersion)
      .toOption
      .flatMap(entries.get) match {
      case s @ Some(_) => hits += 1; s
      case None        => misses += 1; None
    }

  def put(a: LlmAdjudication): Unit = {
    if (!entries.contains(a.adjudicationId)) added += a
    entries(a.adjudicationId) = a
  }

  def preload(a: LlmAdjudication): Unit = entries(a.adjudicationId) = a

  def size: Int = entries.size

  /** Adjudications added in this run, sorted by ID. */
  def newEntries: List[LlmAdjudication] = added.toList.sortBy(_.adjudicationId.value)
}

object AdjudicationCache {

  /** Loads a cache file (JSON lines of [[LlmAdjudication]], each validated). */
  def load(file: Path): AdjudicationCache = {
    val c = new AdjudicationCache
    if (!Files.isRegularFile(file)) throw new ErError(s"LLM cache file $file does not exist")
    DataFiles.readLines(file, file.getFileName.toString) { (line, n) =>
      if (line.trim.nonEmpty)
        Contract.decode[LlmAdjudication](line) match {
          case Right(a) => c.preload(a)
          case Left(e)  => throw new ErError(s"$file:$n: ${e.message}")
        }
    }
    c
  }
}
