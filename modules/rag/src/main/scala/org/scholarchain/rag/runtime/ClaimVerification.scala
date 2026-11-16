package org.scholarchain.rag.runtime

import java.math.{BigDecimal => JBigDecimal, RoundingMode}
import java.util.Locale

import org.scholarchain.core._

/** The verdict on one claim. `validEvidence` are the cited IDs that exist in the bundle; `reasons`
  * explain every check that did not pass; `checked` counts the numbers, names, IDs and titles that
  * did pass.
  */
final case class VerifiedClaim(
    claim: DraftClaim,
    status: VerificationStatus,
    validEvidence: List[EvidenceId],
    reasons: List[String],
    checked: Int
) {
  def kept: Boolean =
    status == VerificationStatus.Verified || status == VerificationStatus.PartiallySupported
}

/** Stage 9, ClaimVerification: deterministic code, never a model. For each claim:
  *   - every cited evidence ID exists in the bundle (others are fabricated identifiers);
  *   - every number in the text equals a value of a cited graph evidence item, rounded to the
  *     precision the claim states (integers exactly; non-integers to at least two decimals); every
  *     year equals a window year, edge year or work year of a cited item. Digits inside stable IDs,
  *     claim IDs, dblp homonym numbers (`Wei Wang 0003`), venue names of cited work items (`ACL
  *     (1)` when a cited work's venue is `conf/acl`), dblp and venue keys of cited items or with
  *     the shape of a real dblp key (`conf/acl/SmithJ16`), and quoted titles are not numbers. Any
  *     other slash token (`99/100`) or `Name (n)` is checked like every other number;
  *   - every number the claim binds (`numbers[]`) equals the named field of the named cited item;
  *   - "N hops" equals the hop count of a cited path item;
  *   - every quoted title equals the title of a cited work item;
  *   - every researcher ID and every capitalized name in the text belongs to a cited item;
  *   - a `semantic_context` item never supports a number, edge, path, component, community or
  *     bridge claim.
  *
  * Status: `conflicting` when a number, year, hop count or bound value contradicts its cited
  * evidence (an unbound non-year number that matches no value of the cited graph items included,
  * Phase 9 m-1); `unsupported` when no valid evidence supports the claim; `verified` when every
  * check passes and at least one check was made; `partially_supported` when some checks pass and
  * others are not covered, or when the claim has nothing checkable (reason
  * [[ClaimVerification.NothingCheckable]]).
  */
object ClaimVerification {

  /** The reason of a claim with valid citations and nothing checkable in its text. */
  val NothingCheckable = "nothing checkable"

  private val StableIdRe =
    "\\b(researcher|work|evidence|edge|run|mention|chunk|link):v1:[0-9a-f]{32}\\b".r
  private val ClaimIdRe = "\\bclaim-[0-9]{1,4}\\b".r
  private val QuotedRe = "\"([^\"]{1,400})\"".r
  private val HomonymRe = "(?<=\\p{L}[\\p{L}.'’-]{0,40}) 0[0-9]{3}\\b".r
  // a capitalized word followed by " (n)"; the "(n)" is dropped only when the word is the venue of
  // a cited work item (its venueKey's last segment, case-insensitive), as in `ACL (1)`
  private val VenueNumRe = "(\\p{Lu}[\\p{L}]{0,30}) \\(([0-9]{1,2})\\)".r
  // a token with a slash; dropped only when it is a dblp or venue key of a cited item, or has the
  // shape of a real dblp key (`conf/acl`, `conf/acl/SmithJ16`, `journals/corr/abs-2101-00001`)
  private val SlashTokenRe = "[^\\s,;()\\[\\]\"]*/[^\\s,;()\\[\\]\"]*".r
  private val DblpKeyRe =
    "(?:conf|journals|books|series|reference|phd|tr|ms)/[a-z][a-z0-9]*(?:/(?=[A-Za-z0-9._-]*[A-Za-z])[A-Za-z0-9._-]+)?".r
  private val NumberRe =
    "(?<![\\p{L}\\p{N}_.:])([0-9]+(?:\\.[0-9]+)?)(?![\\p{L}\\p{N}_]|\\.[0-9])".r
  private val HopRe = "\\b([0-9]+)\\s+(?:hops?|steps?)\\b".r
  private val GraphWords = List(
    "coauthor",
    "collaborat",
    "path",
    "edge",
    "communit",
    "component",
    "bridge",
    "hop",
    "publication",
    "team"
  )

  private def decimals(lit: String): Int = {
    val i = lit.indexOf('.'); if (i < 0) 0 else lit.length - i - 1
  }
  private def decimalsOf(v: Double): Int =
    JBigDecimal.valueOf(v).stripTrailingZeros().scale().max(0)

  /** A stated literal matches a stored value: integers exactly; otherwise rounded (half up) to the
    * literal's decimals, which must be at least min(2, the stored value's decimals).
    */
  def literalMatches(lit: String, stored: Double): Boolean = {
    val d = decimals(lit)
    val x = BigDecimal(lit)
    if (d == 0) stored.isWhole && BigDecimal(stored) == x
    else
      d >= math.min(2, decimalsOf(stored)) &&
      BigDecimal(JBigDecimal.valueOf(stored).setScale(d, RoundingMode.HALF_UP)) == x
  }

  /** A bound number matches a stored value: integers exactly, others within half a unit of the
    * second decimal.
    */
  def boundMatches(value: Double, stored: Double): Boolean =
    if (stored.isWhole) value == stored else math.abs(value - stored) <= 0.005 + 1e-12

  private def normTitle(s: String) =
    s.trim.stripSuffix(".").replace('"', '\'').replaceAll("\\s+", " ").toLowerCase(Locale.ROOT)
  private def normName(s: String) = s.trim.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT)

  def verify(draft: AnswerDraft, bundle: Option[EvidenceBundle]): List[VerifiedClaim] = {
    val items = bundle.map(_.items).getOrElse(Nil).map(i => i.evidenceId -> i).toMap
    draft.claims.map(verifyClaim(_, items))
  }

  def verifyClaim(c: DraftClaim, items: Map[EvidenceId, EvidenceItem]): VerifiedClaim = {
    val cited = c.evidenceIds.map(EvidenceId(_)).distinct
    val valid = cited.filter(items.contains)
    val invalid = cited.filterNot(items.contains)
    val validItems = valid.map(items)
    val graph = validItems.filter(_.evidenceType != EvidenceType.SemanticContext)
    val conflicts = List.newBuilder[String]
    val uncovered = List.newBuilder[String]
    var checked = 0
    invalid.foreach(id => uncovered += s"cites ${id.value}, which is not in the evidence bundle")

    val text = c.text
    // ---- quoted titles ----
    val titles = graph.flatMap(EvidenceValues.title).map(normTitle).toSet
    QuotedRe.findAllMatchIn(text).map(_.group(1)).foreach { t =>
      if (titles.contains(normTitle(t))) checked += 1
      else uncovered += "a quoted title is not the title of a cited work"
    }
    val noQuotes = QuotedRe.replaceAllIn(text, " ")

    // ---- stable IDs ----
    val graphIds = graph.flatMap(EvidenceValues.ids).toSet
    StableIdRe.findAllMatchIn(noQuotes).foreach { m =>
      val id = m.matched
      if (m.group(1) == "evidence") {
        if (valid.exists(_.value == id)) checked += 1
        else uncovered += s"mentions $id, which is not cited"
      } else if (graphIds.contains(id)) checked += 1
      else uncovered += s"identifier $id is not in cited graph evidence"
    }

    // ---- names ----
    val graphNames = graph
      .flatMap(i => EvidenceValues.names(i) ++ EvidenceValues.pairs(i).get("askedName"))
      .map(normName)
      .toSet
    // homonym numbers stay attached here: they are part of a dblp name
    RulePlanner.nameRuns(StableIdRe.replaceAllIn(noQuotes, " ")).foreach { n =>
      if (graphNames.contains(normName(n))) checked += 1
      else uncovered += s"the name '${n.take(60)}' is not in cited evidence"
    }

    // ---- numbers ----
    val citedPairs = graph.map(EvidenceValues.pairs)
    val citedKeys =
      citedPairs.flatMap(p => p.get("dblpKey").toList ++ p.get("venueKey")).filter(_.nonEmpty).toSet
    val citedVenues = citedPairs
      .flatMap(_.get("venueKey"))
      .map(k => k.split('/').last.toLowerCase(Locale.ROOT))
      .filter(_.nonEmpty)
      .toSet
    val keep = (m: scala.util.matching.Regex.Match) =>
      scala.util.matching.Regex.quoteReplacement(m.matched)
    val noIds =
      List(StableIdRe, ClaimIdRe, HomonymRe).foldLeft(noQuotes)((t, re) => re.replaceAllIn(t, " "))
    val noVenues = VenueNumRe.replaceAllIn(
      noIds,
      m =>
        if (citedVenues.contains(m.group(1).toLowerCase(Locale.ROOT)))
          scala.util.matching.Regex.quoteReplacement(m.group(1)) + " "
        else keep(m)
    )
    val stripped = SlashTokenRe.replaceAllIn(
      noVenues,
      m => {
        val tok = m.matched.stripSuffix(".").stripSuffix(":")
        if (citedKeys.contains(tok) || DblpKeyRe.matches(tok)) " " else keep(m)
      }
    )
    val values: List[(String, Double)] = graph.flatMap(i => EvidenceValues.numbers(i).toList)
    val years: Set[Double] = values.collect {
      case (f, v) if EvidenceValues.YearFields.contains(f) => v
    }.toSet
    val paths = graph
      .filter(_.evidenceType == EvidenceType.GraphPath)
      .flatMap(i => EvidenceValues.numbers(i).get("hops"))
    HopRe.findAllMatchIn(stripped).foreach { m =>
      val h = m.group(1).toDouble
      if (paths.contains(h)) checked += 1
      else if (paths.nonEmpty)
        conflicts += s"states ${m.group(1)} hops but the cited path has ${paths.map(Fact.fmt).mkString(", ")}"
      else uncovered += s"states ${m.group(1)} hops without citing a path"
    }
    NumberRe.findAllMatchIn(stripped).map(_.group(1)).foreach { lit =>
      val n = lit.toDouble
      val isYear = !lit.contains('.') && n >= 1900 && n <= 2100
      if (isYear) {
        if (years.contains(n)) checked += 1
        else if (graph.nonEmpty)
          conflicts += s"year $lit is not a window, edge or work year of the cited evidence"
        else uncovered += s"year $lit is not supported by graph evidence"
      } else if (values.exists { case (_, v) => literalMatches(lit, v) }) checked += 1
      // m-1: a number that matches no value of the cited graph items contradicts them
      else if (graph.nonEmpty)
        conflicts += s"number $lit is not a value of the cited graph evidence"
      else uncovered += s"number $lit is not supported by graph evidence"
    }

    // ---- bound numbers ----
    c.numbers.foreach { b =>
      val id = EvidenceId(b.evidenceId)
      items.get(id) match {
        case None =>
          uncovered += s"a number is bound to ${b.evidenceId}, which is not in the bundle"
        case Some(_) if !valid.contains(id) =>
          uncovered += s"a number is bound to ${b.evidenceId}, which the claim does not cite"
        case Some(i) if i.evidenceType == EvidenceType.SemanticContext =>
          uncovered += "a number is bound to semantic context, which never supports numbers"
        case Some(i) =>
          EvidenceValues.numbers(i).get(b.field) match {
            case None         => uncovered += s"the cited item has no field ${b.field}"
            case Some(stored) =>
              if (boundMatches(b.value, stored)) checked += 1
              else
                conflicts += s"binds ${Fact.fmt(b.value)} to ${b.field} of ${b.evidenceId}, whose stored value is ${Fact.fmt(stored)}"
          }
      }
    }

    // ---- semantic context never supports graph facts ----
    val lower = text.toLowerCase(Locale.ROOT)
    val graphish = NumberRe.findFirstIn(stripped).isDefined || GraphWords.exists(lower.contains)
    val semanticOnly = graph.isEmpty && validItems.nonEmpty
    val cs = conflicts.result().distinct
    val us = uncovered.result().distinct
    // m-1: `verified` needs at least one passed check; with nothing checkable the claim is only
    // partially supported
    val nothingCheckable =
      cs.isEmpty && validItems.nonEmpty && !(semanticOnly && graphish) && us.isEmpty && checked == 0
    val status =
      if (cs.nonEmpty) VerificationStatus.Conflicting
      else if (validItems.isEmpty) VerificationStatus.Unsupported
      else if (semanticOnly && graphish) VerificationStatus.Unsupported
      else if (us.isEmpty && checked > 0) VerificationStatus.Verified
      else if (nothingCheckable) VerificationStatus.PartiallySupported
      else if (checked > 0) VerificationStatus.PartiallySupported
      else VerificationStatus.Unsupported
    val reasons =
      cs ++ us ++ (if (semanticOnly && graphish && cs.isEmpty)
                     List(
                       "semantic context cannot support a number, edge, path, component, community or bridge claim"
                     )
                   else Nil) ++
        (if (validItems.isEmpty && cs.isEmpty) List("no cited evidence is in the bundle")
         else Nil) ++
        (if (nothingCheckable) List(ClaimVerification.NothingCheckable) else Nil)
    VerifiedClaim(c, status, valid, reasons.distinct, checked)
  }
}
