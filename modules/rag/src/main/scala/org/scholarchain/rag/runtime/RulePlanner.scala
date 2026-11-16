package org.scholarchain.rag.runtime

import java.util.Locale

import scala.util.matching.Regex

import org.scholarchain.core.{CollaborationMetric, QueryOperation}
import org.scholarchain.core.config.NamedWindow

/** The deterministic planner: parses a question into the same [[PlanDraft]] the planner model
  * returns (schema `query-plan-draft-v1`). It is the fallback when model drafts fail validation and
  * what the `fake` provider uses.
  *
  * It only extracts allowlisted pieces: operation keywords, configured windows (by their years),
  * ranking metrics, small integers, capitalized name runs, ORCID iDs and a bounded topic phrase.
  * Nothing else in the question has any effect, so instructions or database code inside the
  * question cannot change the plan beyond these fields, and no user text becomes query text.
  *
  * Name rule: a run of at least two capitalized tokens (initials like `O.`, hyphens and apostrophes
  * allowed; the particles van, von, de, der, la, le, da, di, del, du, dos, bin, al, ten, ter inside
  * a run), optionally followed by a four-digit dblp homonym number, or any double-quoted text.
  * Leading question words are stripped; all-caps tokens (acronyms, query keywords) break runs; a
  * run containing an institution word (University, Institute, ...) is an institution filter.
  */
final class RulePlanner(windows: List[NamedWindow], bounds: PlanBounds) {
  import RulePlanner._

  def draft(question: String): PlanDraft = {
    var text = QueryRequest.canonicalQuestion(question).take(QueryRequest.MaxQuestionChars)
    val unsupported = List.newBuilder[UnsupportedConstraint]

    // ---- ORCID iDs ----
    val orcids = OrcidRe.findAllIn(text).toList.distinct.take(bounds.maxResearcherNames)
    text = OrcidRe.replaceAllIn(text, " ")

    // ---- windows (years) ----
    val spans = List.newBuilder[(Int, Int)]
    text = RangeRe.replaceAllIn(text, m => { spans += ((m.group(1).toInt, m.group(2).toInt)); " " })
    text =
      BetweenRe.replaceAllIn(text, m => { spans += ((m.group(1).toInt, m.group(2).toInt)); " " })
    val singleYears = SingleYearRe.findAllMatchIn(text).map(_.group(1)).toList
    text = SingleYearRe.replaceAllIn(text, " ")
    val lowerAll = text.toLowerCase(Locale.ROOT)
    val fullWords = FullWindowWords.exists(lowerAll.contains)
    val matched = spans.result().distinct.flatMap { case (a, b) =>
      windows.find(w => w.startYear == a && w.endYear == b) match {
        case Some(w) => Some(w.label)
        case None    =>
          unsupported += UnsupportedConstraint("window", s"$a-$b is not a configured window"); None
      }
    }
    singleYears.distinct.foreach(y =>
      unsupported += UnsupportedConstraint(
        "window",
        s"$y is not a configured window (single years are not windows)"
      )
    )
    val fullLabel = windows.sortBy(w => (w.startYear, -w.endYear)).headOption.map(_.label)
    val windowLabels =
      (matched ++ (if (fullWords && matched.isEmpty) fullLabel.toList else Nil)).distinct

    // ---- topic (removed from the text before names are read) ----
    val topic = TopicRes.view
      .flatMap(r => r.findFirstMatchIn(text))
      .headOption
      .map { m =>
        text = text.substring(0, m.start(1)) + " " + text.substring(m.end(1))
        m.group(1).trim.stripSuffix(".").trim.take(PlanBounds.MaxTopicChars)
      }
      .filter(_.nonEmpty)

    // ---- quoted names ----
    val quoted = QuotedRe
      .findAllMatchIn(text)
      .map(_.group(1).trim)
      .filter(s => s.nonEmpty && s.length <= PlanBounds.MaxNameChars)
      .toList
    text = QuotedRe.replaceAllIn(text, " , ")

    // ---- venue filters ----
    VenueRe
      .findAllMatchIn(text)
      .foreach(m => unsupported += UnsupportedConstraint("venue", s"venue ${m.group(1)}"))
    if (
      VenueWordRe.findFirstIn(text.toLowerCase(Locale.ROOT)).isDefined && VenueRe
        .findFirstIn(text)
        .isEmpty
    )
      unsupported += UnsupportedConstraint("venue", "venue filter")

    // ---- capitalized name runs ----
    val runs = nameRuns(text)
    val (institutions, people) =
      runs.partition(r => r.split(' ').exists(t => InstitutionWords.contains(t)))
    institutions.foreach(i =>
      unsupported += UnsupportedConstraint(
        "institution",
        i.take(UnsupportedConstraint.MaxTextChars)
      )
    )
    if (InstitutionPhraseRe.findFirstIn(lowerAll).isDefined && institutions.isEmpty)
      unsupported += UnsupportedConstraint("institution", "institution filter")
    val allNames = (quoted ++ people).distinct
    if (allNames.size > bounds.maxResearcherNames)
      unsupported += UnsupportedConstraint(
        "other",
        s"more than ${bounds.maxResearcherNames} researcher names"
      )
    val names = allNames.take(bounds.maxResearcherNames)

    // ---- operations ----
    val lower = (" " + text.toLowerCase(Locale.ROOT) + " ").replaceAll("\\s+", " ")
    def has(ws: List[String]): Boolean = ws.exists(lower.contains)
    val n = names.size + orcids.size
    val pathQ = has(PathWords)
    val bridgeQ = has(BridgeWords)
    val communityQ = lower.contains("communit")
    val componentQ = lower.contains("component")
    val temporalQ = has(TemporalWords) || (lower.contains("compare") && windowLabels.size >= 2)
    val teamQ = has(TeamWords)
    val neighborQ = has(NeighborWords)
    val rankingQ = has(RankingWords)
    val metric = metricOf(lower)
    val ops: List[QueryOperation] =
      if (pathQ && n >= 2) List(QueryOperation.ShortestPath)
      else if (bridgeQ && n == 0) List(QueryOperation.BridgeResearchers)
      else if ((communityQ || bridgeQ) && n >= 1) List(QueryOperation.CommunityAnalysis)
      else if (componentQ && n >= 1) List(QueryOperation.ConnectedComponent)
      else if (temporalQ && n >= 1) List(QueryOperation.TemporalComparison)
      else if (teamQ && n >= 1) List(QueryOperation.MaxTeamSize)
      else if (neighborQ && n >= 1) List(QueryOperation.CoauthorNeighborhood)
      else if (n == 0 && (rankingQ || teamQ || topic.isDefined))
        List(QueryOperation.ResearcherRanking) ++ topic.map(_ => QueryOperation.SemanticContext)
      else if (n >= 1 && has(LookupWords) && metric.isEmpty) List(QueryOperation.ResearcherLookup)
      else if (n >= 1) List(QueryOperation.CollaborationBreadth)
      else {
        unsupported += UnsupportedConstraint(
          "other",
          "the question does not map to a supported operation"
        )
        List(QueryOperation.ResearcherLookup)
      }
    val rankingMetric = ops match {
      case List(QueryOperation.BridgeResearchers) => Some(CollaborationMetric.CommunitiesConnected)
      case QueryOperation.ResearcherRanking :: _  =>
        Some(
          metric.getOrElse(
            if (teamQ) CollaborationMetric.MaxCoauthorsOnOnePaper
            else CollaborationMetric.DistinctCoauthors
          )
        )
      case List(QueryOperation.TemporalComparison) | List(QueryOperation.CollaborationBreadth) =>
        metric
      case _ => None
    }
    val limit = LimitRes.view
      .flatMap(_.findFirstMatchIn(lower))
      .headOption
      .flatMap(m => number(m.group(1)))
      .map(x => math.max(1, math.min(bounds.rankingTopK, x)))
    val depth = DepthRe
      .findFirstMatchIn(lower)
      .flatMap(m => number(m.group(1)))
      .map(x => math.max(1, math.min(bounds.maxPathDepth, x)))
    val temporal = ops.contains(QueryOperation.TemporalComparison)
    PlanDraft(
      ops,
      names,
      orcids,
      if (temporal && windowLabels.size >= 2) None else windowLabels.headOption,
      if (temporal && windowLabels.size >= 2) windowLabels.take(windows.size) else Nil,
      rankingMetric,
      limit,
      depth,
      topic,
      unsupported.result().distinct.take(PlanBounds.MaxUnsupported)
    )
  }

  /** Capitalized name runs of `text` (see the class comment). */
  def nameRuns(text: String): List[String] = RulePlanner.nameRuns(text)
}

object RulePlanner {

  /** Policy version recorded in a plan's provenance when the plan came from this parser. */
  val PolicyVersion = "rule-planner/1"

  /** Capitalized name runs of `text`: at least two capitalized tokens (plus inner particles and an
    * optional trailing four-digit homonym number), leading and trailing question words removed.
    * Shared with [[ClaimVerification]], which checks every name it finds in a claim.
    */
  def nameRuns(text: String): List[String] = {
    val tokens = TokenRe.findAllMatchIn(text).toList
    val out = List.newBuilder[String]
    var cur = Vector.empty[String]
    var lastEnd = -1
    def flush(): Unit = {
      var r = cur
      while (r.nonEmpty && (Stop.contains(r.head) || Particles.contains(r.head))) r = r.tail
      while (r.nonEmpty && (Stop.contains(r.last) || Particles.contains(r.last))) r = r.init
      if (r.nonEmpty && r.last.endsWith(".") && r.last.length > 2)
        r = r.init :+ r.last.stripSuffix(".")
      val core = r.filterNot(t => Suffix.matches(t))
      val okSuffix = r.lastOption.exists(Suffix.matches) && r.count(Suffix.matches) == 1
      val wellFormed = r.forall(t => !Suffix.matches(t) || (okSuffix && t == r.last))
      if (
        wellFormed && core.size >= 2 && core.exists(t => !Particles.contains(t)) &&
        r.mkString(" ").length <= PlanBounds.MaxNameChars
      )
        out += r.mkString(" ")
      cur = Vector.empty
    }
    tokens.foreach { m =>
      val raw = m.matched
      val trimmed = raw.reverse.dropWhile(c => c == '\'' || c == '’' || c == '-').reverse
      val t = trimmed.stripSuffix("'s").stripSuffix("’s")
      val adjacent = lastEnd >= 0 && text.substring(lastEnd, m.start) == " "
      if (!adjacent && cur.nonEmpty) flush()
      val isCap = t.headOption.exists(c => Character.isUpperCase(c)) && !isAllCaps(t)
      val isParticle = Particles.contains(t) && cur.nonEmpty
      val isSuffix = Suffix.matches(t) && cur.nonEmpty
      if (isCap || isParticle || isSuffix) cur :+= t else if (cur.nonEmpty) flush()
      if ((t != raw || (t.endsWith(".") && t.length > 2)) && cur.nonEmpty) flush()
      lastEnd = m.end
    }
    if (cur.nonEmpty) flush()
    out.result().distinct
  }

  private val OrcidRe: Regex =
    "(?:https://orcid\\.org/)?[0-9]{4}-[0-9]{4}-[0-9]{4}-[0-9]{3}[0-9X]".r
  private val RangeRe: Regex = "\\b((?:19|20)\\d{2})\\s*[-–—]\\s*((?:19|20)\\d{2})\\b".r
  private val BetweenRe: Regex =
    "(?i)\\b(?:between|from)\\s+((?:19|20)\\d{2})\\s+(?:and|to|until|through)\\s+((?:19|20)\\d{2})\\b".r
  private val SingleYearRe: Regex = "\\b(?:in|during|since|after|before)\\s+((?:19|20)\\d{2})\\b".r
  private val QuotedRe: Regex = "[\"“]([^\"”]{1,120})[\"”]".r
  private val TokenRe: Regex = "[\\p{L}\\p{M}][\\p{L}\\p{M}'’.\\-]*|\\d{4}".r
  private val Suffix: Regex = "\\d{4}".r
  private val VenueRe: Regex =
    "\\b(?:at|in|published in|appeared in)\\s+(?:the\\s+)?([A-Z][A-Za-z]*[A-Z][A-Za-z]*)\\b(?!\\s+[A-Z][a-z])".r
  private val VenueWordRe: Regex = "\\b(venue|conference|journal|proceedings)\\b".r
  private val InstitutionPhraseRe: Regex =
    "\\b(affiliated with|affiliation|institution|employer|working at)\\b".r

  private val TopicRes: List[Regex] = List(
    "(?i)\\bon the topic of\\s+([^?,;.]{2,200}?)(?=\\s+(?:have|has|with|who|in|during|between|by)\\b|[?,;.]|$)".r,
    "(?i)\\b(?:topic|area|field)\\s+(?:of\\s+)?([^?,;.]{2,200}?)(?=\\s+(?:have|has|with|who|in|during|between|by)\\b|[?,;.]|$)".r,
    "(?i)\\bwork(?:ing|s)? on\\s+([^?,;.]{2,200}?)(?=\\s+(?:have|has|with|who|in|during|between|by)\\b|[?,;.]|$)".r,
    "(?i)\\bresearch(?:ing)? on\\s+([^?,;.]{2,200}?)(?=\\s+(?:have|has|with|who|in|during|between|by)\\b|[?,;.]|$)".r,
    "(?i)\\bprofiles? (?:mention|mentions|match|matches|about)\\s+([^?,;.]{2,200}?)(?=\\s+(?:have|has|with|who|in|during|between|by)\\b|[?,;.]|$)".r,
    "(?i)\\brelated to\\s+([^?,;.]{2,200}?)(?=\\s+(?:have|has|with|who|in|during|between|by)\\b|[?,;.]|$)".r
  )

  private val FullWindowWords =
    List("full window", "full period", "whole period", "all years", "entire period")
  private val PathWords = List(
    " path",
    "shortest",
    "degrees of separation",
    "connected through",
    "chain of coauthors",
    "how far apart"
  )
  private val BridgeWords = List(
    "bridge",
    "bridging",
    "broker",
    "connect the most communities",
    "connect many communities",
    "communities connected",
    "span the most communities"
  )
  private val TemporalWords = List(
    " change",
    "changed",
    "over time",
    "across the time windows",
    "across windows",
    "across the windows",
    "across time",
    "evolve",
    "evolved",
    " trend",
    " grow",
    " grew"
  )
  private val TeamWords = List(
    " team",
    "coauthors on one paper",
    "coauthors on a single paper",
    "coauthors on a paper",
    "authors on one paper",
    "most authors",
    "largest paper",
    "biggest paper",
    "coauthors on any paper"
  )
  private val NeighborWords = List(
    "collaborators",
    "co-authors of",
    "coauthors of",
    "worked with",
    "collaborated",
    "collaborate ",
    "coauthored",
    "co-authored",
    "direct collaborat",
    "work with",
    "written with"
  )
  private val RankingWords = List(
    " top ",
    " most ",
    "highest",
    "largest number",
    " rank",
    "which researchers",
    "leading",
    "greatest",
    " best "
  )
  private val LookupWords =
    List("who is", "who's", "tell me about", "describe", "what do we know about")

  private val Stop: Set[String] = Set(
    "Who",
    "Whom",
    "Whose",
    "What",
    "Which",
    "How",
    "When",
    "Where",
    "Why",
    "Is",
    "Are",
    "Was",
    "Were",
    "Does",
    "Do",
    "Did",
    "Has",
    "Have",
    "Had",
    "Can",
    "Could",
    "Would",
    "Should",
    "Will",
    "Between",
    "List",
    "Show",
    "Compare",
    "Find",
    "Give",
    "Tell",
    "Name",
    "Rank",
    "Among",
    "In",
    "From",
    "For",
    "And",
    "Or",
    "Of",
    "The",
    "A",
    "An",
    "Please",
    "Ignore",
    "Also",
    "Then",
    "Print",
    "Run",
    "Return",
    "Describe",
    "Explain",
    "Researchers",
    "Researcher",
    "Top",
    "Over",
    "During",
    "Within",
    "By",
    "With",
    "To",
    "On",
    "At",
    "After",
    "Before",
    "Since",
    "Previous",
    "Instructions",
    "All",
    "Any",
    "Each",
    "Every",
    "Coauthors",
    "Coauthor",
    "Collaborators",
    "Path",
    "Community",
    "Communities",
    "Component",
    "Window",
    "Is",
    "Using",
    "Use",
    "Now",
    "Forget",
    "System",
    "Assistant",
    "User",
    "Note",
    "Answer",
    "Paper",
    "Papers",
    "Team",
    "Me",
    "Us",
    "Our",
    "My",
    "Your",
    "They",
    "He",
    "She",
    "It",
    "This",
    "That",
    "These",
    "Those",
    "If",
    "Not",
    "No",
    "Yes",
    "Shortest",
    "Direct",
    "Directly",
    "Largest",
    "Highest",
    "Most",
    "Publications",
    "Distinct",
    "Weighted",
    "Degree",
    "Bridge",
    "Bridges"
  )
  private val Particles: Set[String] =
    Set(
      "van",
      "von",
      "de",
      "der",
      "den",
      "la",
      "le",
      "da",
      "di",
      "del",
      "du",
      "dos",
      "das",
      "bin",
      "al",
      "ten",
      "ter"
    )
  private val InstitutionWords: Set[String] = Set(
    "University",
    "Universität",
    "Université",
    "Universidad",
    "Institute",
    "Institut",
    "College",
    "Laboratory",
    "Laboratories",
    "Lab",
    "Labs",
    "Inc.",
    "Inc",
    "Corporation",
    "Corp.",
    "Company",
    "School",
    "Academy",
    "Hospital",
    "Center",
    "Centre",
    "Foundation",
    "Research"
  )

  private def isAllCaps(t: String): Boolean = {
    val letters = t.filter(_.isLetter)
    letters.length >= 2 && letters.forall(c => !Character.isLowerCase(c))
  }

  private val Words: Map[String, Int] = Map(
    "one" -> 1,
    "two" -> 2,
    "three" -> 3,
    "four" -> 4,
    "five" -> 5,
    "six" -> 6,
    "seven" -> 7,
    "eight" -> 8,
    "nine" -> 9,
    "ten" -> 10,
    "twenty" -> 20,
    "fifty" -> 50
  )
  private def number(s: String): Option[Int] = s.toIntOption.orElse(Words.get(s))
  private val LimitRes: List[Regex] = List(
    "\\btop\\s+(\\d{1,3}|one|two|three|four|five|six|seven|eight|nine|ten|twenty|fifty)\\b".r,
    "\\b(\\d{1,3}|three|five|ten|twenty)\\s+(?:researchers|people|authors|names)\\b".r
  )
  private val DepthRe: Regex =
    "\\b(?:within|at most|up to|depth(?: of)?)\\s+(\\d)\\s*(?:hops|steps|edges)?\\b".r

  /** The metric a question names, longest phrases first. */
  def metricOf(lower: String): Option[CollaborationMetric] = {
    import CollaborationMetric._
    val rules: List[(List[String], CollaborationMetric)] = List(
      List("participation coefficient") -> ParticipationCoefficient,
      List(
        "cross-community",
        "cross community",
        "coauthors in other communities"
      ) -> CrossCommunityDegree,
      List(
        "communities connected",
        "connect the most communities",
        "connect many communities",
        "bridge",
        "bridging"
      ) -> CommunitiesConnected,
      List("coreness", "k-core", "core number") -> Coreness,
      List("weighted degree", "collaboration intensity", "intensity") -> WeightedDegree,
      List("total coauthor occurrences", "coauthor occurrences") -> TotalCoauthorOccurrences,
      List(
        "average team",
        "average number of coauthors",
        "average coauthors",
        "mean team"
      ) -> AverageCoauthorsPerPaper,
      List(
        "team size",
        "largest team",
        "biggest team",
        "coauthors on one paper",
        "coauthors on a single paper",
        "coauthors on a paper"
      ) -> MaxCoauthorsOnOnePaper,
      List(
        "publications",
        "papers published",
        "number of papers",
        "publication count"
      ) -> PublicationCount,
      List(
        "distinct coauthors",
        "coauthors",
        "co-authors",
        "collaborators",
        "collaboration breadth",
        "breadth"
      ) -> DistinctCoauthors
    )
    rules.collectFirst { case (ws, m) if ws.exists(lower.contains) => m }
  }
}
