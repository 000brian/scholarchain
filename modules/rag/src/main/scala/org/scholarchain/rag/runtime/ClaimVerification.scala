package org.scholarchain.rag.runtime

import java.util.Locale

import scala.util.matching.Regex

import org.scholarchain.core._

/** The verdict on one claim. `validEvidence` are the cited IDs that exist in the bundle; `reasons`
  * explain every check that did not pass, in fixed text ([[ClaimVerification.Reasons]]: never a
  * number, identifier, name or title of the claim, so a removed claim shows no model-made fact);
  * `checked` counts the numbers, years, names, IDs and titles that did pass.
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
  *   - every number in the text (formats of [[NumberFormats]]) equals a value of a cited graph
  *     evidence item, rounded to the precision the claim states, and that item is about the
  *     researcher and the metric the text ties the number to (C-04): within the number's clause, or
  *     else the whole claim, a cited item whose researcher is named there, and, for metric items, a
  *     metric the words there name (`distinct coauthors`, `publications`, ...). A value that only
  *     another researcher's or another metric's item holds is a conflict;
  *   - years by their role (L-2, L-3; a plain 1900-2100 number that equals a non-year value of a
  *     cited graph item is checked as that number, R3-01): a year range (`2015-2025`, `2015–2025`,
  *     `between 2015 and 2025`) must equal the window of a cited item, the first-to-last years of a
  *     cited edge, or the context window of a cited match-decision item; any other year (`from X to
  *     Y`, `since X`, `in X`, ...) must equal a first or last year of a cited edge or the year of a
  *     cited work. A window year alone never supports a single year;
  *   - every number the claim binds (`numbers[]`) equals the named field of the named cited item;
  *   - "N hops" equals the hop count of a cited path item;
  *   - every quoted title equals the title of a cited work item;
  *   - every identifier and every capitalized name in the text belongs to a cited graph item;
  *   - a `semantic_context` item never supports a number, edge, path, component, community or
  *     bridge claim.
  *
  * Digits inside stable IDs, claim IDs, dblp homonym numbers, venue names of cited works (`ACL
  * (1)`), dblp and venue keys of cited items or with the shape of a real dblp key, and quoted
  * titles are not numbers.
  *
  * Status: `conflicting` when a number, year, hop count or bound value contradicts its cited
  * evidence; `unsupported` when no valid evidence supports the claim, or when the text states an
  * identifier, a researcher name or a title that the cited evidence does not hold (T-01, T-10: such
  * a claim is never kept); `verified` when every check passes and at least one check was made;
  * `partially_supported` when some checks pass and others are not covered (a cited ID outside the
  * bundle next to valid ones, a bound number on an item the claim does not cite), or when the claim
  * has nothing checkable (reason [[ClaimVerification.NothingCheckable]]).
  */
object ClaimVerification {

  /** The reason of a claim with valid citations and nothing checkable in its text. */
  val NothingCheckable = "nothing checkable"

  /** Every reason text the verifier gives. Fixed text only (T-01, T-07, C-01). */
  object Reasons {
    val CitedNotInBundle = "a cited evidence ID is not in the evidence bundle"
    val EvidenceIdNotCited = "the text names an evidence ID that the claim does not cite"
    val IdNotCited = "the text names an identifier that the cited evidence does not hold"
    val NameNotCited = "the text names a researcher that the cited evidence does not hold"
    val TitleNotCited = "a quoted title is not the title of a cited work"
    val HopsConflict = "a hop count differs from the cited path"
    val HopsNoPath = "a hop count is stated without citing a path"
    val NumberConflict = "a number is not a value of the cited graph evidence"
    val NumberMisattributed =
      "a number is a value of the cited evidence, but not of the researcher or metric the text ties it to"
    val NumberNoGraph = "a number is not supported by graph evidence"
    val RangeConflict =
      "a year range is not the window of a cited item or the years of a cited edge"
    val YearConflict = "a year is not a first or last year of a cited edge or a cited work's year"
    val YearNoGraph = "a year is not supported by graph evidence"
    val BoundNotInBundle = "a number is bound to an evidence ID that is not in the bundle"
    val BoundNotCited = "a number is bound to an evidence item the claim does not cite"
    val BoundSemantic = "a number is bound to semantic context, which never supports numbers"
    val BoundNoField = "a number is bound to a field the cited item does not have"
    val BoundConflict = "a bound number differs from the stored value of its field"
    val SemanticGraph =
      "semantic context cannot support a number, edge, path, component, community or bridge claim"
    val NoCitedEvidence = "no cited evidence is in the bundle"
    val all: List[String] = List(
      CitedNotInBundle,
      EvidenceIdNotCited,
      IdNotCited,
      NameNotCited,
      TitleNotCited,
      HopsConflict,
      HopsNoPath,
      NumberConflict,
      NumberMisattributed,
      NumberNoGraph,
      RangeConflict,
      YearConflict,
      YearNoGraph,
      BoundNotInBundle,
      BoundNotCited,
      BoundSemantic,
      BoundNoField,
      BoundConflict,
      SemanticGraph,
      NoCitedEvidence,
      NothingCheckable
    )
  }

  import NumberFormats.{ClaimIdRe, DblpKeyRe, HomonymRe, QuotedRe, SlashTokenRe, StableIdRe}

  // a capitalized word followed by " (n)"; the "(n)" is dropped only when the word is the venue of
  // a cited work item (its venueKey's last segment, case-insensitive), as in `ACL (1)`
  private val VenueNumRe = "(\\p{Lu}[\\p{L}]{0,30}) \\(([0-9]{1,2})\\)".r
  private val HopRe = "\\b([0-9]+)\\s+(?:hops?|steps?)\\b".r
  private val Year = "((?:19|20)[0-9]{2})"
  private val RangeRe: Regex = s"(?<![\\p{N}])$Year\\s*[-–—]\\s*$Year(?![\\p{N}])".r
  private val BetweenRe: Regex = s"(?i)\\bbetween\\s+$Year\\s+and\\s+$Year\\b".r
  // clause boundaries: a comma, semicolon or colon before a space (never inside `1,234`), or a
  // conjunction
  private val ClauseSep: Regex = "[,;:](?=\\s|$)|\\s(?:and|while|whereas|but)\\s".r
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

  /** Metric words, most specific first; each match is blanked before the next is tried. A `None`
    * entry names a non-metric field (edge publications), so its words never select a metric.
    */
  private val MetricWords: List[(Regex, Option[CollaborationMetric])] = {
    import CollaborationMetric._
    List(
      "joint publications?|joint papers?|shared publications?|edge weight" -> None,
      "coauthors? in (?:other|another|different) communit(?:y|ies)|cross[- ]community degree" ->
        Some(CrossCommunityDegree),
      "(?:other|different) communities|communities connected|connected communities" ->
        Some(CommunitiesConnected),
      "average coauthors?(?: per paper)?|coauthors? per paper|average team size" ->
        Some(AverageCoauthorsPerPaper),
      "coauthors? (?:at most )?on one paper|maximum coauthors?|max(?:imum)? team size|largest team" ->
        Some(MaxCoauthorsOnOnePaper),
      "distinct[- ]coauthors?|collaboration breadth|direct degree" -> Some(DistinctCoauthors),
      "total coauthor occurrences?|coauthor occurrences?" -> Some(TotalCoauthorOccurrences),
      "weighted degree|collaboration intensity" -> Some(WeightedDegree),
      "participation coefficient" -> Some(ParticipationCoefficient),
      "coreness|k-core" -> Some(Coreness),
      "component" -> Some(ComponentSize),
      "\\bcommunity\\b|\\bmembers\\b" -> Some(CommunitySize),
      "\\bpublications?\\b|\\bpapers\\b" -> Some(PublicationCount)
    ).map { case (re, m) => (s"(?i)(?:$re)".r, m) }
  }

  /** The metrics a piece of text names (empty when it names none). */
  private[runtime] def metricsNamed(text: String): Set[CollaborationMetric] = {
    var t = text
    val out = Set.newBuilder[CollaborationMetric]
    MetricWords.foreach { case (re, m) =>
      if (re.findFirstIn(t).isDefined) {
        m.foreach(out += _)
        t = re.replaceAllIn(t, " ")
      }
    }
    out.result()
  }

  /** A stated literal matches a stored value ([[NumberFormats.literalMatches]]). */
  def literalMatches(lit: String, stored: Double): Boolean =
    NumberFormats.literalMatches(lit, stored)

  /** A bound number matches a stored value: integers exactly, others within half a unit of the
    * second decimal.
    */
  def boundMatches(value: Double, stored: Double): Boolean =
    if (stored.isWhole) value == stored else math.abs(value - stored) <= 0.005 + 1e-12

  private def normTitle(s: String) =
    s.trim.stripSuffix(".").replace('"', '\'').replaceAll("\\s+", " ").toLowerCase(Locale.ROOT)
  private def normName(s: String) = s.trim.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT)

  private def blank(t: String, start: Int, end: Int): String =
    t.substring(0, start) + (" " * (end - start)) + t.substring(end)

  /** Verifies every claim of `draft` against `bundle`. `windows` maps configured window labels to
    * their years (for the context window of match-decision items, L-3).
    */
  def verify(
      draft: AnswerDraft,
      bundle: Option[EvidenceBundle],
      windows: Map[String, YearWindow] = Map.empty
  ): List[VerifiedClaim] = {
    val items = bundle.map(_.items).getOrElse(Nil).map(i => i.evidenceId -> i).toMap
    draft.claims.map(verifyClaim(_, items, windows))
  }

  def verifyClaim(
      c: DraftClaim,
      items: Map[EvidenceId, EvidenceItem],
      windows: Map[String, YearWindow] = Map.empty
  ): VerifiedClaim = {
    val cited = c.evidenceIds.map(EvidenceId(_)).distinct
    val valid = cited.filter(items.contains)
    val invalid = cited.filterNot(items.contains)
    val validItems = valid.map(items)
    val graph = validItems.filter(_.evidenceType != EvidenceType.SemanticContext)
    val conflicts = List.newBuilder[String]
    val unbacked = List.newBuilder[String]
    val uncovered = List.newBuilder[String]
    var checked = 0
    if (invalid.nonEmpty) uncovered += Reasons.CitedNotInBundle

    val text = c.text
    // ---- quoted titles ----
    val titles = graph.flatMap(EvidenceValues.title).map(normTitle).toSet
    QuotedRe.findAllMatchIn(text).map(_.group(1)).foreach { t =>
      if (titles.contains(normTitle(t))) checked += 1
      else unbacked += Reasons.TitleNotCited
    }
    val noQuotes = QuotedRe.replaceAllIn(text, m => " " * m.matched.length)

    // ---- stable IDs (T-01: an identifier the cited evidence lacks is never shown) ----
    val graphIds = graph.flatMap(EvidenceValues.ids).toSet
    StableIdRe.findAllMatchIn(noQuotes).foreach { m =>
      val id = m.matched
      if (m.group(1) == "evidence") {
        if (valid.exists(_.value == id)) checked += 1
        else unbacked += Reasons.EvidenceIdNotCited
      } else if (graphIds.contains(id)) checked += 1
      else unbacked += Reasons.IdNotCited
    }

    // ---- names (T-10: a researcher the cited evidence lacks is never shown) ----
    val graphNames = graph
      .flatMap(i => EvidenceValues.names(i) ++ EvidenceValues.pairs(i).get("askedName"))
      .map(normName)
      .toSet
    // homonym numbers stay attached here: they are part of a dblp name
    RulePlanner.nameRuns(StableIdRe.replaceAllIn(noQuotes, " ")).foreach { n =>
      if (graphNames.contains(normName(n))) checked += 1
      else unbacked += Reasons.NameNotCited
    }

    // ---- text with the non-numbers blanked (positions kept) ----
    val citedPairs = graph.map(EvidenceValues.pairs)
    val citedKeys =
      citedPairs.flatMap(p => p.get("dblpKey").toList ++ p.get("venueKey")).filter(_.nonEmpty).toSet
    val citedVenues = citedPairs
      .flatMap(_.get("venueKey"))
      .map(k => k.split('/').last.toLowerCase(Locale.ROOT))
      .filter(_.nonEmpty)
      .toSet
    val noIds = List(StableIdRe, ClaimIdRe, HomonymRe).foldLeft(noQuotes)((t, re) =>
      re.replaceAllIn(t, m => " " * m.matched.length)
    )
    val noVenues = VenueNumRe.replaceAllIn(
      noIds,
      m =>
        if (citedVenues.contains(m.group(1).toLowerCase(Locale.ROOT)))
          Regex.quoteReplacement(m.group(1)) + (" " * (m.matched.length - m.group(1).length))
        else Regex.quoteReplacement(m.matched)
    )
    val stripped = SlashTokenRe.replaceAllIn(
      noVenues,
      m => {
        val tok = m.matched.stripSuffix(".").stripSuffix(":")
        if (citedKeys.contains(tok) || DblpKeyRe.matches(tok)) " " * m.matched.length
        else Regex.quoteReplacement(m.matched)
      }
    )

    // ---- hop counts ----
    val paths = graph
      .filter(_.evidenceType == EvidenceType.GraphPath)
      .flatMap(i => EvidenceValues.numbers(i).get("hops"))
    HopRe.findAllMatchIn(stripped).foreach { m =>
      val h = m.group(1).toDouble
      if (paths.contains(h)) checked += 1
      else if (paths.nonEmpty) conflicts += Reasons.HopsConflict
      else uncovered += Reasons.HopsNoPath
    }

    // ---- years by role (L-2, L-3) ----
    def yearsOf(i: EvidenceItem, fields: Set[String]) =
      EvidenceValues.numbers(i).collect { case (f, v) if fields.contains(f) => v.toInt }
    val eventYears: Set[Int] =
      graph.flatMap(yearsOf(_, Set("firstYear", "lastYear", "year"))).toSet
    val allowedRanges: Set[(Int, Int)] =
      (graph.flatMap(_.window).map(w => (w.startYear, w.endYear)) ++
        graph.flatMap { i =>
          val n = EvidenceValues.numbers(i)
          (n.get("firstYear"), n.get("lastYear")) match {
            case (Some(a), Some(b)) => List((a.toInt, b.toInt))
            case _                  => Nil
          }
        } ++
        graph
          .filter(_.evidenceType == EvidenceType.MatchDecision)
          .flatMap(i => EvidenceValues.pairs(i).get("contextWindowLabel"))
          .flatMap(windows.get)
          .map(w => (w.startYear, w.endYear))).toSet
    var rest = stripped
    List(RangeRe, BetweenRe).foreach { re =>
      re.findAllMatchIn(rest).toList.foreach { m =>
        val r = (m.group(1).toInt, m.group(2).toInt)
        if (allowedRanges.contains(r)) checked += 1
        else if (graph.nonEmpty) conflicts += Reasons.RangeConflict
        else uncovered += Reasons.YearNoGraph
        rest = blank(rest, m.start, m.end)
      }
    }

    // ---- numbers, tied to their researcher and metric (C-04) ----
    val values: List[(String, Double)] = graph.flatMap(i => EvidenceValues.numbers(i).toList)
    def namesOf(i: EvidenceItem): Set[String] =
      EvidenceValues
        .names(i)
        .map(n => normName(HomonymRe.replaceAllIn(n, "")))
        .filter(_.nonEmpty)
    val citedNames = graph.flatMap(namesOf).toSet
    def named(t: String): Set[String] = {
      val lower = normName(t)
      citedNames.filter(n =>
        s"(?<![\\p{L}])${java.util.regex.Pattern.quote(n)}(?![\\p{L}])".r
          .findFirstIn(lower)
          .isDefined
      )
    }
    val claimNames = named(rest)
    val claimMetrics = metricsNamed(rest)
    val bounds = 0 :: ClauseSep.findAllMatchIn(rest).map(_.end).toList ::: List(rest.length)
    def clauseAt(pos: Int): String = {
      val start = bounds.filter(_ <= pos).max
      val end = ClauseSep.findAllMatchIn(rest).map(_.start).find(_ > pos).getOrElse(rest.length)
      rest.substring(start, math.max(start, end))
    }
    NumberFormats.LiteralRe.findAllMatchIn(rest).foreach { m =>
      val lit = m.group(1)
      val plain = NumberFormats.plain(lit)
      val n = plain.toDouble
      // R3-01: a year-shaped number is a year only when no cited non-year value matches it
      val isYear = lit == plain && !plain.contains('.') && n >= 1900 && n <= 2100 &&
        !values.exists { case (f, v) =>
          !EvidenceValues.YearFields.contains(f) && literalMatches(lit, v)
        }
      if (isYear) {
        if (eventYears.contains(n.toInt)) checked += 1
        else if (graph.nonEmpty) conflicts += Reasons.YearConflict
        else uncovered += Reasons.YearNoGraph
      } else if (graph.isEmpty) uncovered += Reasons.NumberNoGraph
      else {
        val clause = clauseAt(m.start)
        val subjects = Some(named(clause)).filter(_.nonEmpty).getOrElse(claimNames)
        val metrics = Some(metricsNamed(clause)).filter(_.nonEmpty).getOrElse(claimMetrics)
        val candidates = graph.filter { i =>
          val names = namesOf(i)
          val subjectOk = subjects.isEmpty || names.isEmpty || names.exists(subjects.contains)
          val metricOk = i.evidenceType != EvidenceType.Metric || metrics.isEmpty ||
            i.metric.exists(v => metrics.contains(v.metric))
          subjectOk && metricOk
        }
        def holds(is: List[EvidenceItem]) =
          is.exists(i => EvidenceValues.numbers(i).values.exists(literalMatches(lit, _)))
        if (holds(candidates)) checked += 1
        else if (values.exists { case (_, v) => literalMatches(lit, v) })
          conflicts += Reasons.NumberMisattributed
        // m-1: a number that matches no value of the cited graph items contradicts them
        else conflicts += Reasons.NumberConflict
      }
    }

    // ---- bound numbers ----
    c.numbers.foreach { b =>
      val id = EvidenceId(b.evidenceId)
      items.get(id) match {
        case None                           => uncovered += Reasons.BoundNotInBundle
        case Some(_) if !valid.contains(id) => uncovered += Reasons.BoundNotCited
        case Some(i) if i.evidenceType == EvidenceType.SemanticContext =>
          uncovered += Reasons.BoundSemantic
        case Some(i) =>
          EvidenceValues.numbers(i).get(b.field) match {
            case None         => uncovered += Reasons.BoundNoField
            case Some(stored) =>
              if (boundMatches(b.value, stored)) checked += 1
              else conflicts += Reasons.BoundConflict
          }
      }
    }

    // ---- semantic context never supports graph facts ----
    val lower = text.toLowerCase(Locale.ROOT)
    val graphish =
      NumberFormats.LiteralRe.findFirstIn(stripped).isDefined || GraphWords.exists(lower.contains)
    val semanticOnly = graph.isEmpty && validItems.nonEmpty
    val cs = conflicts.result().distinct
    val ub = unbacked.result().distinct
    val us = uncovered.result().distinct
    // m-1: `verified` needs at least one passed check; with nothing checkable the claim is only
    // partially supported
    val nothingCheckable =
      cs.isEmpty && ub.isEmpty && validItems.nonEmpty && !(semanticOnly && graphish) &&
        us.isEmpty && checked == 0
    val status =
      if (cs.nonEmpty) VerificationStatus.Conflicting
      else if (validItems.isEmpty) VerificationStatus.Unsupported
      else if (ub.nonEmpty) VerificationStatus.Unsupported
      else if (semanticOnly && graphish) VerificationStatus.Unsupported
      else if (us.isEmpty && checked > 0) VerificationStatus.Verified
      else if (nothingCheckable) VerificationStatus.PartiallySupported
      else if (checked > 0) VerificationStatus.PartiallySupported
      else VerificationStatus.Unsupported
    val reasons =
      cs ++ ub ++ us ++ (if (semanticOnly && graphish && cs.isEmpty) List(Reasons.SemanticGraph)
                         else Nil) ++
        (if (validItems.isEmpty && cs.isEmpty) List(Reasons.NoCitedEvidence) else Nil) ++
        (if (nothingCheckable) List(NothingCheckable) else Nil)
    VerifiedClaim(c, status, valid, reasons.distinct, checked)
  }
}
