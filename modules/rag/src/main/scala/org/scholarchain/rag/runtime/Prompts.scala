package org.scholarchain.rag.runtime

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import scala.util.control.NonFatal

import org.scholarchain.core.QueryAudit

/** One versioned runtime prompt, loaded by path from `prompts/runtime/<promptId>.md`. The file has
  * a `=== SYSTEM ===` section and a `=== USER ===` section; `{{NAME}}` placeholders are filled in
  * one pass (a value is never re-scanned, so user text cannot inject a placeholder). The version
  * that goes into the audit is `<promptId>@sha256:<hex of the whole file>`; changing a prompt means
  * a new file with a new promptId.
  */
final case class PromptTemplate(promptId: String, fileText: String) {
  val version: String = QueryAudit.promptVersion(promptId, fileText)

  private val sections: (String, String) = {
    val s = fileText.indexOf(PromptTemplate.SystemMarker)
    val u = fileText.indexOf(PromptTemplate.UserMarker)
    require(
      s >= 0 && u > s,
      s"$promptId: needs ${PromptTemplate.SystemMarker} then ${PromptTemplate.UserMarker}"
    )
    (
      fileText.substring(s + PromptTemplate.SystemMarker.length, u).trim,
      fileText.substring(u + PromptTemplate.UserMarker.length).trim
    )
  }
  private def systemTemplate: String = sections._1
  private def userTemplate: String = sections._2

  def placeholders: Set[String] =
    PromptTemplate.Placeholder
      .findAllMatchIn(systemTemplate + "\n" + userTemplate)
      .map(_.group(1))
      .toSet

  /** (system, user) with every placeholder filled; Left when a placeholder has no value. */
  def render(values: Map[String, String]): Either[String, (String, String)] = {
    val missing = placeholders -- values.keySet
    if (missing.nonEmpty) Left(s"$promptId: no value for ${missing.toList.sorted.mkString(", ")}")
    else {
      def fill(t: String) =
        PromptTemplate.Placeholder.replaceAllIn(
          t,
          m => java.util.regex.Matcher.quoteReplacement(values(m.group(1)))
        )
      Right((fill(systemTemplate), fill(userTemplate)))
    }
  }
}

object PromptTemplate {
  val SystemMarker = "=== SYSTEM ==="
  val UserMarker = "=== USER ==="
  private val Placeholder = "\\{\\{([A-Z_]+)\\}\\}".r
}

/** The two runtime prompts of one workflow and the answer-claims schema the synthesis prompt asks
  * for (the planner's schema is always `query-plan-draft-v1`).
  */
final case class PromptSet(
    planner: PromptTemplate,
    synthesis: PromptTemplate,
    claims: AnswerClaimsSpec = AnswerClaimsSpec.V1
) {
  def versions: List[String] = List(planner.version, synthesis.version)

  /** Output schemas used with these prompts. */
  def schemaIds: List[String] =
    List(PlanDraftSchema.SchemaId, claims.schemaId, SemanticContextSchema.SchemaId)
}

/** One named prompt set: the prompt IDs and the claims schema they go with. The configuration has
  * no prompt key (core's `llm` section is unchanged), so the set is chosen by the caller
  * (`--prompts` of the benchmark runner, [[RuntimeService]] options); the default is
  * [[Prompts.Current]].
  */
final case class PromptSetSpec(
    name: String,
    plannerId: String,
    synthesisId: String,
    claims: AnswerClaimsSpec
)

object Prompts {

  /** Phase 9 prompts (kept unchanged as the record of that run). */
  val V1: PromptSetSpec =
    PromptSetSpec("v1", "query-planner-v1", "answer-synthesis-v1", AnswerClaimsSpec.V1)

  /** Phase 10 prompts: q26 venue-as-topic rule, smaller synthesis output (decision 5b, 5c). */
  val V2: PromptSetSpec =
    PromptSetSpec("v2", "query-planner-v2", "answer-synthesis-v2", AnswerClaimsSpec.V2)

  /** The prompt set used unless a caller names another. */
  val Current: PromptSetSpec = V2
  val Sets: List[PromptSetSpec] = List(V1, V2)

  def setNamed(name: String): Either[String, PromptSetSpec] =
    Sets
      .find(_.name == name)
      .toRight(s"unknown prompt set '$name' (known: ${Sets.map(_.name).mkString(", ")})")

  val PlannerId: String = Current.plannerId
  val SynthesisId: String = Current.synthesisId

  /** Every schema file under `prompts/schemas/`. */
  val SchemaIds: List[String] =
    List("query-plan-draft-v1", "answer-claims-v1", "answer-claims-v2", "semantic-context-v1")

  /** Loads `<root>/runtime/<promptId>.md`. */
  def load(root: Path, promptId: String): Either[String, PromptTemplate] = {
    val p = root.resolve("runtime").resolve(s"$promptId.md")
    try {
      if (!promptId.matches("[a-z0-9][a-z0-9._-]*")) Left(s"bad prompt ID '$promptId'")
      else if (!Files.isRegularFile(p)) Left(s"prompt file $p does not exist")
      else
        Right(PromptTemplate(promptId, new String(Files.readAllBytes(p), StandardCharsets.UTF_8)))
    } catch { case NonFatal(e) => Left(s"cannot load prompt $promptId: ${e.getMessage}") }
  }

  def loadSet(root: Path): Either[String, PromptSet] = loadSet(root, Current)

  def loadSet(root: Path, spec: PromptSetSpec): Either[String, PromptSet] =
    for {
      p <- load(root, spec.plannerId)
      s <- load(root, spec.synthesisId)
    } yield PromptSet(p, s, spec.claims)

  /** The schema file of `schemaId` under `<root>/schemas/`. */
  def schemaFile(root: Path, schemaId: String): Path =
    root.resolve("schemas").resolve(s"$schemaId.schema.json")

  /** Finds the `prompts` directory: `start` or the nearest ancestor holding `prompts/runtime`. */
  def locate(start: Path = Paths.get("").toAbsolutePath): Either[String, Path] = {
    var cur = start.toAbsolutePath.normalize
    var found: Option[Path] = None
    while (found.isEmpty && cur != null) {
      val c = cur.resolve("prompts")
      if (Files.isDirectory(c.resolve("runtime"))) found = Some(c)
      cur = cur.getParent
    }
    found.toRight(s"no prompts/runtime directory at or above $start")
  }

  /** User or retrieved text placed inside a prompt: the delimiter tags are neutralized so the text
    * cannot close its own data block, and control characters are removed.
    */
  def asData(text: String, maxChars: Int): String =
    Option(text)
      .getOrElse("")
      .replaceAll("(?i)</?\\s*(question|untrusted[a-z_-]*|excerpt)[^>]*>", "[tag removed]")
      .filter(c => !Character.isISOControl(c) || c == '\n')
      .take(maxChars)
}
