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
  *     counts outside such a clause are checked as positive hop counts (P16-A-09); inside the
  *     clause only a bounded hop count ("within N hops") or a depth is a searched depth (P16-K-01).
  *     Fail closed (P16-L-03..L-06, human rule): absence wording is checked whether or not a
  *     `path=none` item is cited, and it is verified only when the claim names exactly two
  *     researchers that resolve (homonym numbers included) to two distinct researcher IDs, a cited
  *     complete `path=none` item is for exactly that ID pair with `maxDepth` at least the stated
  *     depth and a window equal to the claim's window (or neither has one), no year-like phrase is
  *     left unparsed, and no cited found path or edge connects the pair. Phase 17 (decision 6,
  *     P16-L-10..L-15) adds one fail-closed rule that only adds reasons ([[AbsenceRule]]): absence
  *     is recognised by predicate and negation scope (synonyms such as "disconnected" included); an
  *     unclear scope or a word outside a closed vocabulary is ambiguous (at most partially
  *     supported); a possible third name, unnamed researchers, a time qualifier, a missing depth or
  *     a found path in the bundle with both researchers anywhere on it blocks `verified`;
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
    // Phase 17 decision 6 (P16-L-10): wording that may state an absence, but whose negation
    // scope, predicate or words are not ones the absence rule can check (at most partially
    // supported, never verified)
    val AbsenceAmbiguous =
      "the claim may state that researchers are not connected, in wording the verifier cannot check"
    // Phase 17 repair (P17-A-05): the Phase 17 rule names the actual cause of a blocked absence
    // claim (each makes the claim unsupported, as AbsenceNotSearched did for these causes)
    val AbsenceThirdName =
      "an absence claim may name a researcher other than the researcher pair of the path search"
    val AbsenceUnnamed = "an absence claim is about researchers it does not name"
    val AbsenceTimeQualifier =
      "an absence claim states a time qualifier, an unparsed year or a year range other than its window"
    val AbsenceFoundPath =
      "an absence claim is about a researcher pair that a found path or edge in the evidence bundle connects"
    // Phase 17 repair (P17-A-01): a claim that names researchers whom a found path or edge in the
    // bundle connects must state that connection in wording the verifier reads as affirmative (at
    // most partially supported otherwise, never verified)
    val ConnectionUnconfirmed =
      "the claim names researchers that a found path connects, but its wording does not affirm the connection in a form the verifier can check"
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
      AbsenceAmbiguous,
      AbsenceThirdName,
      AbsenceUnnamed,
      AbsenceTimeQualifier,
      AbsenceFoundPath,
      ConnectionUnconfirmed,
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

  /** The clauses (start, end) of `text` that the Phase 16 phrase list ([[AbsencePhraseRe]]) finds:
    * each holds a negation that governs a path phrase (Phase 16 L2, P16-A-09). Uncertainty phrases
    * are blanked first. Positions refer to `text`. Kept unchanged so that nothing it recognised
    * stops being recognised (decision 6.2, monotonic); [[AbsenceRule]] adds to it.
    */
  private def phraseSpans(text: String): List[(Int, Int)] = {
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

  /** The clauses (start, end) of `text`, split as absence clauses are (uncertainty phrases blanked
    * first, curly apostrophes read as straight ones). Positions refer to `text`.
    */
  private[runtime] def absenceClauses(text: String): (String, List[(Int, Int)]) = {
    val t = AbsenceRule.normalize(UncertainRe.replaceAllIn(text, m => " " * m.matched.length))
    val seps = AbsenceClauseSep.findAllMatchIn(t).toList
    val bounds = 0 :: seps.map(_.end)
    val ends = seps.map(_.start) :+ t.length
    (t, bounds.zip(ends).filter { case (s, e) => e > s })
  }

  /** The clauses (start, end) of `text` that state that researchers are not connected: the Phase 16
    * phrase list, plus every clause that [[AbsenceRule.classify]] reads as an absence statement
    * (P16-L-10). Positions refer to `text`.
    */
  private[runtime] def absenceSpans(text: String): List[(Int, Int)] = {
    val (t, clauses) = absenceClauses(text)
    val byRule = clauses.filter { case (s, e) =>
      val c = t.substring(s, e)
      AbsenceRule.classify(c, AbsenceRule.namesSomeone(c, Set.empty)) == AbsenceRule.Absence
    }
    (phraseSpans(text) ++ byRule).distinct
  }

  /** The text states that researchers are not connected (Phase 16 L2, P16-L-10). */
  private[runtime] def statesAbsence(text: String): Boolean = absenceSpans(text).nonEmpty
  private val Year = "((?:19|20)[0-9]{2})"
  private val RangeRe: Regex = s"(?<![\\p{N}])$Year\\s*[-–—]\\s*$Year(?![\\p{N}])".r
  private val BetweenRe: Regex = s"(?i)\\bbetween\\s+$Year\\s+and\\s+$Year\\b".r
  // P16-L-06: a year or year-like token (`2019`, `2010s`, `'19`) outside a parsed range
  private val YearLikeRe: Regex =
    "(?<![\\p{L}\\p{N}])(?:(?:19|20)[0-9]{2}s?|['’][0-9]{2}s?)(?![\\p{L}\\p{N}])".r
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
  ): VerifiedClaim = evaluate(c, items, windows, absenceRule = true)

  /** The Phase 16 verdict: the same checks with the Phase 17 absence rule switched off. Used only
    * as the baseline of the monotonic tests (decision 6.2): the rule only adds reasons, so a
    * claim's status under [[verifyClaim]] is never higher than here.
    */
  private[runtime] def verifyClaimPhase16(
      c: DraftClaim,
      items: Map[EvidenceId, EvidenceItem],
      windows: Map[String, YearWindow] = Map.empty
  ): VerifiedClaim = evaluate(c, items, windows, absenceRule = false)

  private def evaluate(
      c: DraftClaim,
      items: Map[EvidenceId, EvidenceItem],
      windows: Map[String, YearWindow],
      absenceRule: Boolean
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
    // an absence depth; every other hop count is a positive hop count and is checked below.
    // P16-L-03: absence wording is checked whether or not a `path=none` item is cited
    // (Phase 17: these are the Phase 16 phrase spans; the absence rule below adds its own)
    val spans = phraseSpans(stripped)
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
    // P16-L-03..L-06, the human's fail-closed rule: an absence clause is verified only when ALL
    // hold:
    //   - the claim names exactly two researchers, and each name (homonym number included)
    //     resolves to exactly one researcher ID of the cited items, the two IDs distinct (a third
    //     named researcher, an unknown or an ambiguous name fails);
    //   - a cited `path=none` item is for exactly that ID pair;
    //   - that item's search is complete and its `maxDepth` is at least every stated depth;
    //   - its window equals the claim's window: the one year range the clause states, else the one
    //     range the claim states, else none (an item window never equals "none"); two ranges, or a
    //     year-like token the range syntax does not parse ("from 2015 to 2025", "since 2019",
    //     "2015 through 2025"), give no window and fail;
    //   - no cited found path (path item with a hop count, or a coauthor edge, one hop) connects
    //     the pair. Such a path inside the claim's window (or with no usable window) and within
    //     the stated depth (or with none stated) contradicts the absence (HopsConflict); any other
    //     cited found path for the pair makes the clause unsupported (AbsenceNotSearched).
    // A missing depth stays `AbsenceNoDepth` (uncovered, never verified), as in Phase 16 L2.
    def yw(i: EvidenceItem): Option[(Int, Int)] = i.window.map(w => (w.startYear, w.endYear))
    def rangesIn(t: String): Set[(Int, Int)] =
      (RangeRe.findAllMatchIn(t) ++ BetweenRe.findAllMatchIn(t))
        .map(m => (m.group(1).toInt, m.group(2).toInt))
        .toSet
    def isComplete(i: EvidenceItem): Boolean =
      EvidenceValues.pairs(i).get("complete").contains("true")
    // the researcher ID pair of a path or edge item
    def idPair(i: EvidenceItem): Option[Set[String]] = {
      val p = EvidenceValues.pairs(i)
      (p.get("researcherA"), p.get("researcherB")) match {
        case (Some(a), Some(b)) => Some(Set(a, b))
        case _                  =>
          p.get("pathIds").map(_.split(",").toList).collect {
            case ids if ids.size >= 2 => Set(ids.head, ids.last)
          }
      }
    }
    // found connections: path items with a hop count, and coauthor edges (one hop) that exist
    val foundEdges = graph.filter(i =>
      i.evidenceType == EvidenceType.CoauthorEdge &&
        !EvidenceValues.pairs(i).get("found").contains("false")
    )
    def hopsOf(i: EvidenceItem): Option[Double] =
      if (i.evidenceType == EvidenceType.CoauthorEdge) Some(1.0)
      else EvidenceValues.numbers(i).get("hops")
    lazy val idsByName: Map[String, Set[String]] = graph
      .flatMap { i =>
        val p = EvidenceValues.pairs(i)
        val single =
          List("name" -> "researcherId", "nameA" -> "researcherA", "nameB" -> "researcherB")
            .flatMap { case (n, r) =>
              p.get(n).zip(p.get(r)).map { case (a, b) => normName(a) -> b }
            }
        val listed = (p.get("pathNames"), p.get("pathIds")) match {
          case (Some(ns), Some(is)) =>
            val nl = ns.split(" \\| ").toList
            val il = is.split(",").toList
            if (nl.size == il.size) nl.map(normName).zip(il) else Nil
          case _ => Nil
        }
        single ++ listed
      }
      .groupMap(_._1)(_._2)
      .map { case (n, ids) => n -> ids.toSet }
    // the researchers the claim names: capitalized name runs (homonym numbers attached) plus any
    // cited full name that no run already holds
    lazy val claimNamed: List[String] = {
      val src = List(StableIdRe, ClaimIdRe).foldLeft(noQuotes)((t, re) =>
        re.replaceAllIn(t, m => " " * m.matched.length)
      )
      val runs = RulePlanner.nameRuns(src).map(normName).distinct
      val extra = idsByName.keySet.toList.sorted.filter(n =>
        AbsenceRule.wordIn(normName(src), n) && !runs.exists(r => AbsenceRule.wordIn(r, n))
      )
      (runs ++ extra).distinct
    }
    // the pair's researcher IDs and the two names that resolved to them
    lazy val claimPairNamed: Option[(Set[String], List[String])] = {
      val named = claimNamed
      val ids = named.flatMap(n => idsByName.get(n).filter(_.size == 1).toList.flatten)
      if (named.size == 2 && ids.size == 2 && ids.distinct.size == 2) Some((ids.toSet, named))
      else None
    }
    lazy val claimPair: Option[Set[String]] = claimPairNamed.map(_._1)
    // year-like tokens outside the parsed ranges make the claim's window unknown (P16-L-06)
    lazy val unparsedYear: Boolean = {
      val noRanges = List(RangeRe, BetweenRe).foldLeft(stripped)((t, re) =>
        re.replaceAllIn(t, m => " " * m.matched.length)
      )
      YearLikeRe.findFirstIn(noRanges).isDefined
    }
    lazy val claimRanges = rangesIn(stripped)
    // Right(window) when the claim's window is known (None: the claim states none)
    def windowOf(clause: String): Either[Unit, Option[(Int, Int)]] = {
      val clauseRanges = rangesIn(clause)
      if (unparsedYear) Left(())
      else if (clauseRanges.size == 1) Right(clauseRanges.headOption)
      else if (clauseRanges.size > 1) Left(())
      else if (claimRanges.size == 1) Right(claimRanges.headOption)
      else if (claimRanges.isEmpty) Right(None)
      else Left(())
    }
    spans.foreach { case sp @ (s, e) =>
      val clause = stripped.substring(s, e)
      val depths = depthMatches.collect { case (`sp`, m) => m.group(1).toDouble }
      val window = windowOf(clause)
      claimPair match {
        case None      => unbacked += Reasons.AbsenceNotSearched
        case Some(ids) =>
          val pairNone = absentItems.filter(i => idPair(i).contains(ids))
          val pairFound = (foundPaths ++ foundEdges).filter(i => idPair(i).contains(ids))
          if (pairNone.isEmpty) unbacked += Reasons.AbsenceNotSearched
          else {
            val windowed = window.toOption.toList.flatMap(w => pairNone.filter(i => yw(i) == w))
            if (windowed.isEmpty) unbacked += Reasons.AbsenceNotSearched
            // with no window match, the depth is still checked against the complete searches for
            // the pair, so the other reasons are reported too (the claim is unsupported anyway)
            val support =
              if (windowed.nonEmpty) windowed.filter(isComplete) else pairNone.filter(isComplete)
            if (support.isEmpty) unbacked += Reasons.AbsenceBoundedSearch
            else if (depths.isEmpty) uncovered += Reasons.AbsenceNoDepth
            else {
              val maxDepths = support.flatMap(i => EvidenceValues.numbers(i).get("maxDepth"))
              depths.foreach { d =>
                if (maxDepths.exists(_ >= d)) checked += 1
                else unbacked += Reasons.AbsenceBeyondDepth
              }
            }
          }
          val contradicted = pairFound.exists { f =>
            val hopsOk = hopsOf(f).exists(h => depths.isEmpty || depths.exists(h <= _))
            val windowOk = window match {
              case Right(Some((ws, we))) =>
                yw(f).exists { case (fs, fe) => fs >= ws && fe <= we }
              case _ => true
            }
            hopsOk && windowOk
          }
          if (contradicted) conflicts += Reasons.HopsConflict
          else if (pairFound.nonEmpty) unbacked += Reasons.AbsenceNotSearched
      }
    }

    // ---- Phase 17 absence rule (decision 6; P16-L-10..L-15): it only ADDS reasons ----
    // Nothing above is changed by it and `checked` is never raised, so no status can rise
    // (decision 6.2). An absence claim is verified only when every condition of item 1f holds;
    // each condition that fails adds its reason (the lists and the scope rule: [[AbsenceRule]]).
    if (absenceRule) {
      val (t, clauses) = absenceClauses(stripped)
      val bundleNames: Set[String] =
        items.values.flatMap(EvidenceValues.names).map(normName).filter(_.nonEmpty).toSet
      val kinds = clauses.map { case sp @ (s, e) =>
        val c = t.substring(s, e)
        sp -> AbsenceRule.classify(c, AbsenceRule.namesSomeone(c, bundleNames))
      }
      def overlapsPhrase(sp: (Int, Int)): Boolean =
        spans.exists { case (ps, pe) => ps < sp._2 && sp._1 < pe }
      // (a) the absence spans: the Phase 16 phrase spans plus every clause read as an absence
      val ruleSpans = kinds.collect { case (sp, AbsenceRule.Absence) => sp }
      val allSpans = (spans ++ ruleSpans).distinct
      val namesTwo = claimNamed.size >= 2
      val words = AbsenceRule.tokens(t)
      val pairNames = claimPairNamed.map(_._2).getOrElse(Nil)
      val pairParts = pairNames.flatMap(AbsenceRule.parts).toSet
      val labels = windows.keySet.map(_.toLowerCase(Locale.ROOT))
      // (a) unclear scope, both kinds of predicate, a negation beside the pair with no listed
      // predicate; (g) a word outside the closed vocabulary of absence claims
      val unclear = (namesTwo || allSpans.nonEmpty) && kinds.exists(_._2 == AbsenceRule.Unclear)
      val mixed = allSpans.nonEmpty && kinds.exists(_._2 == AbsenceRule.Connection)
      val negationOnly = namesTwo && kinds.exists { case (sp, k) =>
        k == AbsenceRule.NegationOnly && !overlapsPhrase(sp)
      }
      val unknownWord = allSpans.nonEmpty &&
        words.exists(w => !AbsenceRule.inVocabulary(w, pairParts, labels))
      val ambiguous = unclear || mixed || negationOnly || unknownWord
      if (ambiguous) uncovered += Reasons.AbsenceAmbiguous
      // (e, P16-L-15) an absence about researchers the claim does not name
      if ((allSpans.nonEmpty || ambiguous) && words.exists(w => AbsenceRule.UnnamedWords(w.word)))
        unbacked += Reasons.AbsenceUnnamed
      // (c) found paths and coauthor edges of the whole bundle (cited or not)
      val bundleGraph = items.values.toList.filter(_.evidenceType != EvidenceType.SemanticContext)
      val bundleFound = bundleGraph.filter { i =>
        val p = EvidenceValues.pairs(i)
        (i.evidenceType == EvidenceType.GraphPath && EvidenceValues.numbers(i).contains("hops")) ||
        (i.evidenceType == EvidenceType.CoauthorEdge && !p.get("found").contains("false"))
      }
      val foundSet = (foundPaths ++ foundEdges ++ bundleFound).distinct
      // the hops between the pair on a found path (anywhere on it) or edge (one hop)
      def hopsBetween(f: EvidenceItem, ids: Set[String]): Option[Double] = {
        val p = EvidenceValues.pairs(f)
        if (f.evidenceType == EvidenceType.CoauthorEdge)
          (p.get("researcherA"), p.get("researcherB")) match {
            case (Some(a), Some(b)) if Set(a, b) == ids => Some(1.0)
            case _                                      => None
          }
        else
          p.get("pathIds").map(_.split(",").toList).flatMap { path =>
            val at = ids.toList.map(path.indexOf)
            if (ids.size == 2 && at.forall(_ >= 0)) Some(math.abs(at.head - at.last).toDouble)
            else None
          }
      }
      allSpans.foreach { case (s, e) =>
        val depths = (BoundHopRe.findAllMatchIn(stripped) ++ DepthRe.findAllMatchIn(stripped))
          .filter(m => m.start >= s && m.end <= e)
          .map(_.group(1).toDouble)
          .toList
        val window = windowOf(stripped.substring(s, e))
        val win = window.toOption.flatten
        // (f) positive eligibility: each condition is established from the evidence, and the
        // claim can be verified only when all of them are (never because no blocker matched)
        val established: List[(Boolean, String, Boolean)] = claimPairNamed match {
          case None             => List((false, Reasons.AbsenceNotSearched, false))
          case Some((ids, nms)) =>
            val otherParts = bundleNames.filterNot(nms.contains).flatMap(AbsenceRule.parts)
            val pairNone = absentItems.filter(i => idPair(i).contains(ids))
            val windowed = window.toOption.toList.flatMap(w => pairNone.filter(i => yw(i) == w))
            val support = windowed.filter(isComplete)
            val maxDepths = support.flatMap(i => EvidenceValues.numbers(i).get("maxDepth"))
            val through = foundSet.flatMap(f => hopsBetween(f, ids).map(f -> _))
            val contradicted = through.exists { case (f, h) =>
              val hopsOk = depths.isEmpty || depths.exists(h <= _)
              val windowOk = win match {
                case Some((ws, we)) => yw(f).exists { case (fs, fe) => fs >= ws && fe <= we }
                case None           => true
              }
              hopsOk && windowOk
            }
            List(
              // (a) recognised without ambiguity (its reason was added above)
              (!ambiguous, Reasons.AbsenceAmbiguous, false),
              // (b) no possible third name: capitalised and not the pair's, or a name part of
              // another researcher in the bundle
              (
                !words.exists(w => AbsenceRule.isThirdName(w, pairParts, otherParts)),
                Reasons.AbsenceThirdName,
                false
              ),
              // (e, P16-L-14) a stated depth (a search is always depth-limited)
              (depths.nonEmpty, Reasons.AbsenceNoDepth, false),
              // (d) the claim's window is known, every range in it is that window, and no year
              // carries a time qualifier
              (window.isRight, Reasons.AbsenceTimeQualifier, false),
              (win.forall(w => claimRanges.forall(_ == w)), Reasons.AbsenceTimeQualifier, false),
              (
                AbsenceRule.TimeQualifierRe.findFirstIn(stripped).isEmpty,
                Reasons.AbsenceTimeQualifier,
                false
              ),
              // a cited `path=none` search for exactly the pair in that window, complete, and at
              // least as deep as every stated depth
              (windowed.nonEmpty, Reasons.AbsenceNotSearched, false),
              (windowed.isEmpty || support.nonEmpty, Reasons.AbsenceBoundedSearch, false),
              (
                support.isEmpty || depths.forall(d => maxDepths.exists(_ >= d)),
                Reasons.AbsenceBeyondDepth,
                false
              ),
              // (c) no found path with both researchers anywhere on it, and no edge between them
              (!contradicted, Reasons.HopsConflict, true),
              (contradicted || through.isEmpty, Reasons.AbsenceFoundPath, false)
            )
        }
        // every condition not established adds its reason, so a span that is not eligible
        // (`established.forall(_._1)` false) can never leave the claim verified
        established.foreach { case (ok, reason, conflict) =>
          if (!ok) {
            if (conflict) conflicts += reason
            else if (reason == Reasons.AbsenceAmbiguous) uncovered += reason
            else unbacked += reason
          }
        }
      }

      // (h) Phase 17 repair, P17-A-01: a structural rule that does not depend on listing every
      // absence phrasing. When the claim names two researchers whom a found path (anywhere on it)
      // or an edge of the bundle connects, it can be verified only when its wording affirms that
      // connection in a form the verifier reads: a clause read as a connection whose words before
      // its predicate all belong to the closed vocabulary ([[AbsenceRule.affirms]]), or a claim
      // with no predicate and no negation at all whose every word belongs to it (a quantity of the
      // pair, such as joint publications). Otherwise ConnectionUnconfirmed (at most partially
      // supported). An absence wording the lists recognise was already checked above (conflicting
      // when the found path contradicts it).
      val namedIds: List[String] = claimNamed
        .flatMap(n => idsByName.get(n).filter(_.size == 1).toList.flatten)
        .distinct
      val connectedPairs: List[Set[String]] = namedIds
        .combinations(2)
        .map(_.toSet)
        .filter(ids => foundSet.exists(f => hopsBetween(f, ids).isDefined))
        .toList
      if (connectedPairs.nonEmpty) {
        val knownParts =
          claimNamed.flatMap(AbsenceRule.parts).toSet ++ bundleNames.flatMap(AbsenceRule.parts)
        def known(w: AbsenceRule.Tok): Boolean =
          AbsenceRule.inAffirmVocabulary(w, knownParts, labels)
        val readings = clauses.map { case sp @ (s, e) =>
          val c = t.substring(s, e)
          sp -> AbsenceRule.read(c, AbsenceRule.namesSomeone(c, bundleNames))
        }
        val affirmed = clauses.exists { case (s, e) =>
          AbsenceRule.affirms(t.substring(s, e), known)
        }
        val quantity = kinds.forall(_._2 == AbsenceRule.Neutral) && words.forall(known)
        if (!affirmed && !quantity) uncovered += Reasons.ConnectionUnconfirmed
        // (c on possible absence, P17-A-04): with no affirmed connection, an unclear clause that
        // may state an absence (an absence predicate not negated, or a connection predicate under
        // a tight negation, in a clause that names nobody or holds both kinds) is contradicted, as
        // an absence clause is, by a found path or edge between a connected pair named in the claim
        // inside the clause's window and within its stated depth
        if (!affirmed) readings.foreach { case (sp @ (s, e), r) =>
          if (r.kind == AbsenceRule.Unclear && r.mayStateAbsence && !allSpans.contains(sp)) {
            val depths = (BoundHopRe.findAllMatchIn(stripped) ++ DepthRe.findAllMatchIn(stripped))
              .filter(m => m.start >= s && m.end <= e)
              .map(_.group(1).toDouble)
              .toList
            val win = windowOf(stripped.substring(s, e)).toOption.flatten
            val contradicted = connectedPairs.exists { ids =>
              foundSet.exists { f =>
                hopsBetween(f, ids).exists { h =>
                  val hopsOk = depths.isEmpty || depths.exists(h <= _)
                  val windowOk = win match {
                    case Some((ws, we)) => yw(f).exists { case (fs, fe) => fs >= ws && fe <= we }
                    case None           => true
                  }
                  hopsOk && windowOk
                }
              }
            }
            if (contradicted) conflicts += Reasons.HopsConflict
          }
        }
      }
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

/** Phase 17 decision 6 (P16-L-10..L-15): how a claim about the absence of a connection is
  * recognised, in one place. A small, tested rule over word tokens and the absence clauses of
  * [[ClaimVerification]], not a language parser.
  *
  * Recognition (item a), per clause:
  *   - a connection predicate ([[ConnectionWords]], [[ConnectionPhrases]]) governed by a negation
  *     ([[Negations]], or any word ending in `n't`) is an absence statement;
  *   - an absence predicate ([[AbsenceWords]], [[AbsencePhrases]]) that is not negated is an
  *     absence statement; a negated one ("not disconnected") states a connection;
  *   - a negation governs a predicate when it comes before it in the clause and every word between
  *     them is a scope word ([[ScopeWords]]) or, for a connection predicate, another connection
  *     predicate ("no path connects"). `not only`, `not just`, `not merely`, `not simply` are not
  *     negations;
  *   - the scope is unclear ([[Unclear]]) when a negation comes before a predicate with other words
  *     between ("no evidence that A and B are disconnected"), when a negation follows every
  *     predicate of its clause, when the clause holds absence and connection predicates, or when an
  *     absence statement names no researcher ("although they never coauthored").
  *
  * Eligibility (items b, d, e, g) uses [[isThirdName]], [[Allowlist]], [[TimeQualifierRe]],
  * [[UnnamedWords]] and [[inVocabulary]] ([[FunctionWords]] is the closed list of function and
  * scope words, built from the correct absence claims of the fixtures and the deterministic
  * synthesis wording).
  *
  * Phase 17 repair (P17-A-01, P17-A-04): a claim that names two researchers whom a found path or
  * edge of the bundle connects is verified only when [[affirms]] reads a clause as an affirmed
  * connection (closed vocabulary [[inAffirmVocabulary]] before its predicate), or the claim has no
  * predicate and no negation and only closed-vocabulary words; this does not depend on listing
  * absence phrasings. An [[Unclear]] clause that may state an absence ([[Reading]]) is checked
  * against those found paths when no clause affirms the connection.
  */
private[runtime] object AbsenceRule {

  /** A clause's reading. */
  sealed trait Kind
  case object Absence extends Kind
  case object Connection extends Kind
  case object Unclear extends Kind

  /** A negation with no predicate in its clause ("A is not a direct coauthor of B"). */
  case object NegationOnly extends Kind
  case object Neutral extends Kind

  /** Negation words (item a); every word ending in `n't` is a negation too. */
  val Negations: Set[String] =
    Set(
      "no",
      "not",
      "never",
      "none",
      "without",
      "cannot",
      "neither",
      "nor",
      "nothing",
      "nobody",
      // Phase 17 repair (P17-A-01)
      "lack",
      "lacks",
      "lacked",
      "lacking"
    )

  /** Words after `not` that make it emphatic, not a negation ("not only connected but ..."). */
  val NotEmphatic: Set[String] = Set("only", "just", "merely", "simply")

  /** Connection predicates (item a i). */
  val ConnectionWords: Set[String] = Set(
    "connected",
    "connects",
    "connect",
    "connection",
    "connections",
    "path",
    "paths",
    "linked",
    "link",
    "links",
    "reachable",
    "reach",
    "reached",
    "collaborated",
    "collaborate",
    "collaborates",
    "coauthored",
    "co-authored",
    "coauthors",
    "co-authors",
    "route",
    "routes",
    "chain",
    "chains",
    "joined"
  )

  /** Connection predicates of more than one word (matched before single words). */
  val ConnectionPhrases: List[List[String]] =
    List("same connected component", "same component").map(_.split(' ').toList)

  /** Absence predicates (item a ii). */
  val AbsenceWords: Set[String] = Set(
    "disconnected",
    "unconnected",
    "unreachable",
    "unlinked",
    "isolated",
    "separate",
    "separated",
    "apart",
    // Phase 17 repair (P17-A-01)
    "absent",
    "unrelated"
  )

  /** Absence predicates of more than one word (matched before single words). */
  val AbsencePhrases: List[List[String]] = List(
    "different connected components",
    "different connected component",
    "different components",
    "different component",
    "another connected component",
    "another component",
    // Phase 17 repair (P17-A-01)
    "distinct connected components",
    "distinct connected component",
    "distinct components",
    "distinct component"
  ).map(_.split(' ').toList)

  /** Words that may stand between a negation and the predicate it governs. */
  val ScopeWords: Set[String] = Set(
    "a",
    "an",
    "the",
    "any",
    "be",
    "been",
    "is",
    "are",
    "was",
    "were",
    "have",
    "has",
    "had",
    "do",
    "does",
    "did",
    "even",
    "ever",
    "yet",
    "directly",
    "indirectly",
    "mutually",
    "in",
    "such",
    "known",
    "direct",
    "indirect",
    "coauthorship",
    "co-authorship",
    "collaboration",
    "collaborative",
    "connecting",
    "shortest",
    "other"
  )

  /** Function and scope words an absence claim may use (item g), from the correct absence claims of
    * the fixtures ("No coauthorship path between A and B exists within 6 hops in 2015-2025.", "A
    * and B are not connected within 4 hops in 2015-2025.", "No path between A and B exists within
    * depth 6 in w2021-2023.", "A is not connected to B ...") and the deterministic synthesis
    * wording ("In 2015-2025, no coauthorship path between A and B exists within depth 6 (the
    * bounded search completed).").
    */
  val FunctionWords: Set[String] = Set(
    "of",
    "in",
    "within",
    "between",
    "up",
    "to",
    "at",
    "most",
    "hops",
    "hop",
    "steps",
    "step",
    "depth",
    "limit",
    "path",
    "the",
    "and",
    "any",
    "coauthorship",
    "window",
    "exists",
    "exist",
    "existed",
    "is",
    "are",
    "was",
    "were",
    "be",
    "been",
    "have",
    "has",
    "had",
    "there",
    "bounded",
    "search",
    "completed"
  )

  /** Words that are never a third name (item b i): sentence starters, month names, and the metric
    * and status words the verifier knows. Lower case.
    */
  val Allowlist: Set[String] = Set(
    "the",
    "there",
    "no",
    "not",
    "none",
    "neither",
    "nor",
    "never",
    "in",
    "within",
    "between",
    "both",
    "a",
    "an",
    "and",
    "of",
    "to",
    "up",
    "at",
    "from",
    "during",
    "although",
    "though",
    "however",
    "while",
    "but",
    "yet",
    "they",
    "it",
    "this",
    "these",
    "is",
    "are",
    "was",
    "were",
    "since",
    "before",
    "after",
    "until",
    "for",
    "with",
    "by",
    "as",
    "january",
    "february",
    "march",
    "april",
    "may",
    "june",
    "july",
    "august",
    "september",
    "october",
    "november",
    "december",
    "path",
    "coauthorship",
    "search",
    "depth",
    "hops",
    "window",
    "complete",
    "completed",
    "bounded",
    "verified"
  )

  /** Words that make an absence claim about researchers it does not name (P16-L-15). */
  val UnnamedWords: Set[String] = Set(
    "anyone",
    "anybody",
    "someone",
    "somebody",
    "everyone",
    "everybody",
    "nobody",
    "others",
    "people",
    "researchers",
    "colleagues",
    "collaborators",
    "common",
    "shared",
    "share",
    "shares",
    "mutual",
    "his",
    "her",
    "hers",
    "him",
    "their",
    "theirs",
    "them",
    "who",
    "whom",
    "whose"
  )

  /** A year used with a time qualifier (item d, P16-L-13). */
  val TimeQualifierRe: scala.util.matching.Regex =
    ("(?i)\\b(?:before|after|since|until|till|prior(?:\\s+to)?|between)\\s+" +
      "(?:the\\s+)?(?:year\\s+)?(?:19|20)[0-9]{2}").r

  // numbers, years, year ranges and window labels (`full-2015-2025`, `w2021-2023`)
  private val NumberTok =
    "(?:[0-9]+(?:[.,][0-9]+)*|(?:full-|w)?(?:19|20)[0-9]{2}(?:-(?:19|20)[0-9]{2})?s?)".r
  private val TokRe = "[\\p{L}\\p{N}]+(?:['-][\\p{L}\\p{N}]+)*".r

  /** A word token: lower case (a trailing `'s` removed), its raw text and position. */
  final case class Tok(word: String, raw: String, start: Int, end: Int)

  /** Curly apostrophes read as straight ones (same length, so positions are kept). */
  def normalize(t: String): String = t.replace('’', '\'').replace('‘', '\'')

  def tokens(text: String): Vector[Tok] =
    TokRe
      .findAllMatchIn(normalize(text))
      .map { m =>
        val w = m.matched.toLowerCase(Locale.ROOT).stripSuffix("'s")
        Tok(w, m.matched, m.start, m.end)
      }
      .toVector

  /** The lower-case word parts of a name. */
  def parts(name: String): Set[String] = tokens(name).map(_.word).toSet

  /** `n` occurs in `t` as whole words. */
  def wordIn(t: String, n: String): Boolean =
    n.nonEmpty && s"(?<![\\p{L}\\p{N}])${java.util.regex.Pattern.quote(n)}(?![\\p{L}\\p{N}])".r
      .findFirstIn(t)
      .isDefined

  def isNegation(w: String): Boolean = Negations.contains(w) || w.endsWith("n't")

  private def capitalised(t: Tok): Boolean = t.raw.headOption.exists(Character.isUpperCase)

  /** The clause names a researcher: a capitalised word outside [[Allowlist]] (one-word names
    * count), a name run, or a name of the bundle.
    */
  def namesSomeone(clause: String, bundleNames: Set[String]): Boolean = {
    val lower = clause.toLowerCase(Locale.ROOT)
    tokens(clause).exists(t => capitalised(t) && !Allowlist(t.word) && !isNegation(t.word)) ||
    RulePlanner.nameRuns(clause).nonEmpty || bundleNames.exists(n => wordIn(lower, n))
  }

  /** A clause's reading: its [[Kind]]; whether some predicate is read as an absence (an absence
    * predicate not negated, or a connection predicate under a tight negation), so an [[Unclear]]
    * clause may state an absence (P17-A-04); and the token index of the first predicate read as a
    * connection without negation, or of a negated absence predicate (P17-A-01).
    */
  final case class Reading(kind: Kind, mayStateAbsence: Boolean, affirmAt: Option[Int])

  /** The reading of one clause (see the object comment). */
  def classify(clause: String, namesSomeone: Boolean): Kind = read(clause, namesSomeone).kind

  /** P17-A-01: the clause affirms a connection: it reads as [[Connection]] and every word before
    * its first affirmative predicate is `known` (the closed vocabulary of [[inAffirmVocabulary]]),
    * so an unlisted word that may negate it ("lack", "devoid of") leaves the connection unaffirmed.
    */
  def affirms(clause: String, known: Tok => Boolean): Boolean = {
    val r = read(clause, namesSomeone = true)
    r.kind == Connection && r.affirmAt.exists(k => tokens(clause).take(k).forall(known))
  }

  /** The reading of one clause, with its details (see [[Reading]]). */
  def read(clause: String, namesSomeone: Boolean): Reading = {
    val w = tokens(clause).map(_.word)
    val n = w.size
    // predicates: (start, end exclusive, isConnection); phrases first, their words consumed
    val preds = List.newBuilder[(Int, Int, Boolean)]
    var i = 0
    while (i < n) {
      def phraseAt(ps: List[List[String]]) =
        ps.find(p => i + p.size <= n && w.slice(i, i + p.size).toList == p)
      phraseAt(ConnectionPhrases)
        .map(p => (p.size, true))
        .orElse(
          phraseAt(AbsencePhrases).map(p => (p.size, false))
        ) match {
        case Some((len, conn)) => preds += ((i, i + len, conn)); i += len
        case None              =>
          if (ConnectionWords(w(i))) preds += ((i, i + 1, true))
          else if (AbsenceWords(w(i))) preds += ((i, i + 1, false))
          i += 1
      }
    }
    val ps = preds.result()
    val negs = w.indices.filter { j =>
      isNegation(w(j)) && !(w(j) == "not" && j + 1 < n && NotEmphatic(w(j + 1)))
    }.toList
    val connAt = ps.filter(_._3).flatMap(p => p._1 until p._2).toSet
    if (ps.isEmpty) Reading(if (negs.nonEmpty) NegationOnly else Neutral, false, None)
    else {
      // per predicate: Some(true) governed tightly, Some(false) negation before it with other
      // words between (unclear), None no negation before it
      val readings = ps.map { case (s, _, conn) =>
        val before = negs.filter(_ < s)
        val tight =
          before.exists(j => (j + 1 until s).forall(k => ScopeWords(w(k)) || (conn && connAt(k))))
        val gov = if (tight) Some(true) else if (before.nonEmpty) Some(false) else None
        (conn, gov)
      }
      def isAbsence(r: (Boolean, Option[Boolean])) = r match {
        case (true, Some(true)) => true
        case (false, None)      => true
        case _                  => false
      }
      def isConnection(r: (Boolean, Option[Boolean])) = r match {
        case (true, None)        => true
        case (false, Some(true)) => true
        case _                   => false
      }
      val dangling = negs.exists(j => !ps.exists(_._1 > j))
      val loose = readings.exists(_._2.contains(false))
      val absence = readings.exists(isAbsence)
      val connection = readings.exists(isConnection)
      val affirmAt = ps.zip(readings).collectFirst { case ((s, _, _), r) if isConnection(r) => s }
      val kind =
        if (loose || dangling || (absence && connection)) Unclear
        else if (absence) { if (namesSomeone) Absence else Unclear }
        else Connection
      Reading(kind, absence, affirmAt)
    }
  }

  /** P17-A-01: words a claim about a connected pair may use besides [[inVocabulary]]: the emphatic
    * words after `not`, and the words of the deterministic pair, path, edge and metric wordings
    * (Synthesis.scala). Closed: any other word leaves the connection unaffirmed.
    */
  val AffirmWords: Set[String] = Set(
    "only",
    "just",
    "merely",
    "simply",
    "share",
    "shares",
    "shared",
    "joint",
    "publication",
    "publications",
    "paper",
    "papers",
    "edge",
    "weight",
    "weighted",
    "distinct",
    "coauthor",
    "co-author",
    "direct",
    "degree",
    "breadth",
    "intensity",
    "occurrences",
    "total",
    "other",
    "another",
    "community",
    "communities",
    "members",
    "researchers",
    "rank",
    "by",
    "with",
    "from",
    "via",
    "through",
    "shortest"
  )

  /** P17-A-01: the closed vocabulary of a claim about a connected pair: [[inVocabulary]] (with the
    * name parts of the researchers the claim or the bundle names) plus [[AffirmWords]].
    */
  def inAffirmVocabulary(t: Tok, nameParts: Set[String], windowLabels: Set[String]): Boolean =
    inVocabulary(t, nameParts, windowLabels) || AffirmWords(t.word)

  /** Item b: a possible third name. */
  def isThirdName(t: Tok, pairParts: Set[String], otherParts: Set[String]): Boolean =
    !pairParts(t.word) && !NumberTok.matches(t.word) && (
      (capitalised(t) && !Allowlist(t.word) && !isNegation(t.word)) || otherParts(t.word)
    )

  /** Item g: the word belongs to the closed vocabulary of absence claims. */
  def inVocabulary(t: Tok, pairParts: Set[String], windowLabels: Set[String]): Boolean = {
    val x = t.word
    pairParts(x) || isNegation(x) || ConnectionWords(x) || AbsenceWords(x) ||
    (ConnectionPhrases ++ AbsencePhrases).exists(_.contains(x)) || ScopeWords(x) ||
    FunctionWords(x) || Allowlist(x) || NumberTok.matches(x) || windowLabels(x)
  }
}
