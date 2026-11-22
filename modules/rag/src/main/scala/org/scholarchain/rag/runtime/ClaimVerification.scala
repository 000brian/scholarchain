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
  *   - "N hops" equals the hop count of a cited path item; against a cited complete `path=none`
  *     item whose `maxDepth` is at least N it is a conflict (P16-A-09);
  *   - an absence claim ("no path within N hops", "not connected within depth N") citing a
  *     `path=none` item is supported only by a complete search whose `maxDepth` is at least N; a
  *     search that stopped early (time or graph store resource limit) never supports it (Phase 16
  *     L2). Only a clause whose negation governs the path phrase is an absence statement; hop
  *     counts outside such a clause are checked as positive hop counts (P16-A-09). The search must
  *     be for the researcher pair the clause names and over the window the clause (or else the
  *     claim) states, and a cited found path for that pair inside that window contradicts the
  *     absence; inside the clause only a bounded hop count ("within N hops") or a depth is a
  *     searched depth (P16-K-01);
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
    // Phase 16 L2: absence claims ("no path within N hops") against `path=none` items
    val AbsenceBoundedSearch =
      "an absence claim cites a path search that stopped early, so whether a path exists is unknown"
    val AbsenceBeyondDepth = "an absence claim states a depth beyond the completed path search"
    val AbsenceNoDepth = "an absence claim states no depth limit, but the path search was bounded"
    // P16-K-01: the named pair and the stated window must be the ones a cited search covered
    val AbsenceNotSearched =
      "an absence claim states a researcher pair or window that no cited path search covered"
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
      AbsenceBoundedSearch,
      AbsenceBeyondDepth,
      AbsenceNoDepth,
      AbsenceNotSearched,
      NothingCheckable
    )
  }

  import NumberFormats.{ClaimIdRe, DblpKeyRe, HomonymRe, QuotedRe, SlashTokenRe, StableIdRe}

  // a capitalized word followed by " (n)"; the "(n)" is dropped only when the word is the venue of
  // a cited work item (its venueKey's last segment, case-insensitive), as in `ACL (1)`
  private val VenueNumRe = "(\\p{Lu}[\\p{L}]{0,30}) \\(([0-9]{1,2})\\)".r
  private val HopRe = "\\b([0-9]+)\\s+(?:hops?|steps?)\\b".r
  // Phase 16 L2: a path absence statement. Uncertainty phrases are blanked first, so "did not
  // complete; whether a path exists is unknown" is not an absence claim, while a governed absence
  // phrase beside it still is ("did not complete, but no path exists")
  private val UncertainRe: Regex =
    ("(?i)\\b(?:did not|didn't|could not|couldn't|cannot|can't)\\s+(?:be\\s+)?" +
      "(?:complete|completed|finish|finished|determine|determined|decide|decided|tell)\\b|" +
      "(?i)\\bnot\\s+(?:known|determined|decided)\\b").r
  // P16-A-09: a negation counts only when it governs the path phrase: "no [up to two modifiers]
  // path/connection", "not [up to two fillers] connected/linked/reachable", "not within N hops" or
  // "not within depth N". A negation elsewhere ("is not a direct coauthor; they are connected
  // within 3 hops", "although they never coauthored, ... linked within 3 hops") is not an absence
  // statement, so its hop counts are checked as positive hop counts.
  private val AbsencePhraseRe: Regex =
    ("(?i)\\b(?:no|without)\\s+(?:(?:any|such|known|direct|indirect|coauthorship|co-authorship|" +
      "collaboration|collaborative|connecting|shortest|other)\\s+){0,2}" +
      "(?:paths?|connections?|routes?|chains?)\\b|" +
      "(?i)\\b(?:not|never|neither|nor|isn't|aren't|wasn't|weren't|doesn't|don't|didn't|cannot|" +
      "can't)\\s+(?:(?:be|been|directly|indirectly|even|yet|mutually|find|found|any|a|have|has)" +
      "\\s+){0,2}(?:connected|connects?|linked|links?|reachable|reached|reach|joined|paths?)\\b|" +
      "(?i)\\bnot\\s+(?:even\\s+)?(?:with)?in\\s+(?:depth\\s+(?:limit\\s+)?(?:of\\s+)?[0-9]+|" +
      "[0-9]+\\s+(?:hops?|steps?))\\b").r
  // the bounds of an absence phrase's clause: a comma, semicolon or colon before a space, a
  // sentence end after a word of two or more characters (never the initial of `J. Smith`), a
  // contrastive conjunction, or `and` that starts a new clause ("and they ..."); a plain `and`
  // between two names ("Alice Smith and Bob Jones") does not end the clause
  private val AbsenceClauseSep: Regex =
    ("(?i)[,;:](?=\\s|$)|(?<=[\\p{L}\\p{N}]{2})[.!?](?=\\s|$)|" +
      "\\s(?:but|while|whereas|although|though|yet|however)\\s|" +
      "\\sand\\s+(?=(?:they|there|it|he|she|both|are|is|was|were|have|has)\\b)").r
  private val DepthRe: Regex = "(?i)\\bdepth\\s+(?:limit\\s+)?(?:of\\s+)?([0-9]+)\\b".r
  // P16-K-01: inside an absence clause, a hop count is a searched depth only when a bound word
  // governs it ("within 6 hops", "up to 6 hops", "at most 6 hops"); any other hop count there
  // ("shorter than the 2 hops between ...") states a connection and is a positive hop count
  private val BoundHopRe: Regex =
    "(?i)\\b(?:within|up\\s+to|at\\s+most)\\s+(?:even\\s+)?([0-9]+)\\s+(?:hops?|steps?)\\b".r

  /** The clauses (start, end) of `text` that state that no path exists: each clause holds a
    * negation that governs a path phrase (Phase 16 L2, P16-A-09). Uncertainty phrases are blanked
    * first. Positions refer to `text`.
    */
  private[runtime] def absenceSpans(text: String): List[(Int, Int)] = {
    val t = UncertainRe.replaceAllIn(text, m => " " * m.matched.length)
    val seps = AbsenceClauseSep.findAllMatchIn(t).toList
    AbsencePhraseRe
      .findAllMatchIn(t)
      .map { m =>
        val start = seps.map(_.end).filter(_ <= m.start).maxOption.getOrElse(0)
        val end = seps.map(_.start).filter(_ >= m.end).minOption.getOrElse(t.length)
        (start, end)
      }
      .toList
      .distinct
  }

  /** The text states that no path exists (Phase 16 L2). */
  private[runtime] def statesAbsence(text: String): Boolean = absenceSpans(text).nonEmpty
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

    // ---- path absence (Phase 16 L2) ----
    // An absence claim citing a `path=none` item is checked against that item's completeness and
    // searched depth: only a complete search supports it, and only up to its `maxDepth`. A bounded
    // search (time or resource limit, `complete=false`) never supports it.
    val pathItems = graph.filter(_.evidenceType == EvidenceType.GraphPath)
    val absentItems = pathItems.filter(i => EvidenceValues.pairs(i).get("found").contains("false"))
    val foundPaths = pathItems.filter(i => EvidenceValues.numbers(i).contains("hops"))
    // P16-A-09: only the clauses whose negation governs a path phrase are absence statements;
    // P16-K-01: inside them, only a bounded hop count ("within 6 hops") or a depth ("depth 6") is
    // an absence depth; every other hop count is a positive hop count and is checked below
    val spans = if (absentItems.nonEmpty) absenceSpans(stripped) else Nil
    val depthMatches: List[((Int, Int), Regex.Match)] = spans.flatMap { case sp @ (s, e) =>
      (BoundHopRe.findAllMatchIn(stripped) ++ DepthRe.findAllMatchIn(stripped))
        .filter(m => m.start >= s && m.end <= e)
        .map(sp -> _)
        .toList
    }
    def isAbsenceDepth(m: Regex.Match): Boolean =
      depthMatches.exists { case (_, d) => d.start <= m.start && m.end <= d.end }
    val completeDepths = absentItems
      .filter(i => EvidenceValues.pairs(i).get("complete").contains("true"))
      .flatMap(i => EvidenceValues.numbers(i).get("maxDepth"))
    // P16-K-01: an absence clause is about one researcher pair and one or more windows. The pair is
    // the endpoints of a cited path item whose two names the clause states (or the whole claim,
    // when the clause names fewer than two endpoints). The windows are the year ranges the clause
    // states, else the ranges the claim states. With no stated range the decision is conservative:
    // the cited path items for the pair must all share one window, which is then the clause's
    // window, and any cited found path for the pair contradicts the absence. Support needs a
    // complete `path=none` item for that pair whose window equals each window and whose `maxDepth`
    // is at least each stated depth. A cited found path for the pair whose window lies within a
    // stated window (a path in a sub-window also exists in the wider window) and whose hop count
    // is within the stated depth contradicts the absence (HopsConflict).
    def baseName(n: String): String = normName(HomonymRe.replaceAllIn(n, ""))
    def mentions(t: String, n: String): Boolean = {
      val b = baseName(n)
      b.nonEmpty && s"(?<![\\p{L}])${java.util.regex.Pattern.quote(b)}(?![\\p{L}])".r
        .findFirstIn(normName(t))
        .isDefined
    }
    def endpoints(i: EvidenceItem): Option[(String, String)] = {
      val p = EvidenceValues.pairs(i)
      (p.get("nameA"), p.get("nameB")) match {
        case (Some(a), Some(b)) => Some((a, b))
        case _                  =>
          p.get("pathNames").map(_.split(" \\| ").toList).collect {
            case ns if ns.size >= 2 => (ns.head, ns.last)
          }
      }
    }
    def yw(i: EvidenceItem): Option[(Int, Int)] = i.window.map(w => (w.startYear, w.endYear))
    def rangesIn(t: String): Set[(Int, Int)] =
      (RangeRe.findAllMatchIn(t) ++ BetweenRe.findAllMatchIn(t))
        .map(m => (m.group(1).toInt, m.group(2).toInt))
        .toSet
    val endpointNames =
      (absentItems ++ foundPaths).flatMap(endpoints).flatMap(p => List(p._1, p._2)).distinct
    spans.foreach { case sp @ (s, e) =>
      val clause = stripped.substring(s, e)
      val depths = depthMatches.collect { case (`sp`, m) => m.group(1).toDouble }
      val nameText = if (endpointNames.count(mentions(clause, _)) >= 2) clause else stripped
      def aboutPair(i: EvidenceItem): Boolean =
        endpoints(i).exists { case (a, b) => mentions(nameText, a) && mentions(nameText, b) }
      val pairNone = absentItems.filter(aboutPair)
      val pairFound = foundPaths.filter(aboutPair)
      val stated = Some(rangesIn(clause)).filter(_.nonEmpty).getOrElse(rangesIn(stripped))
      val windows =
        if (stated.nonEmpty) stated
        else
          Some((pairNone ++ pairFound).flatMap(yw).toSet).filter(_.size == 1).getOrElse(Set.empty)
      if (pairNone.isEmpty || windows.isEmpty) unbacked += Reasons.AbsenceNotSearched
      else
        windows.foreach { w =>
          val inWindow = pairNone.filter(i => yw(i).contains(w))
          val maxDepths = inWindow
            .filter(i => EvidenceValues.pairs(i).get("complete").contains("true"))
            .flatMap(i => EvidenceValues.numbers(i).get("maxDepth"))
          if (inWindow.isEmpty) unbacked += Reasons.AbsenceNotSearched
          else if (maxDepths.isEmpty) unbacked += Reasons.AbsenceBoundedSearch
          else if (depths.isEmpty) uncovered += Reasons.AbsenceNoDepth
          else
            depths.foreach { d =>
              if (maxDepths.exists(_ >= d)) checked += 1
              else unbacked += Reasons.AbsenceBeyondDepth
            }
        }
      val contradicted = pairFound.exists { f =>
        val hopsOk = EvidenceValues
          .numbers(f)
          .get("hops")
          .exists(h => depths.isEmpty || depths.exists(h <= _))
        val windowOk = stated.isEmpty ||
          stated.exists { case (ws, we) => yw(f).exists { case (fs, fe) => fs >= ws && fe <= we } }
        hopsOk && windowOk
      }
      if (contradicted) conflicts += Reasons.HopsConflict
    }

    // ---- hop counts (of found paths; an absence statement's depth was checked above) ----
    // P16-A-09: a positive hop count N against a complete search that found no path within a
    // depth of at least N contradicts that search
    val paths = graph
      .filter(_.evidenceType == EvidenceType.GraphPath)
      .flatMap(i => EvidenceValues.numbers(i).get("hops"))
    HopRe.findAllMatchIn(stripped).filterNot(isAbsenceDepth).foreach { m =>
      val h = m.group(1).toDouble
      if (paths.contains(h)) checked += 1
      else if (paths.nonEmpty) conflicts += Reasons.HopsConflict
      else if (completeDepths.exists(_ >= h)) conflicts += Reasons.HopsConflict
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
    // an absence statement's depths were checked above; they are not free numbers (every other
    // hop count stays, as before)
    var rest =
      depthMatches.foldLeft(stripped) { case (t, (_, m)) => blank(t, m.start, m.end) }
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
