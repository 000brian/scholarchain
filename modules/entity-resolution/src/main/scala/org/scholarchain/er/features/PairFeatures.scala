package org.scholarchain.er.features

import org.scholarchain.core.{CodePointOrder, MatchFeature}
import org.scholarchain.er.data.Corpus
import org.scholarchain.er.normalize.{JaroWinkler, NameParts, TitleText}
import org.scholarchain.er.policy.{PairFeatureNames, Policy}

/** The features of one mention pair: the J06 features of the candidate (reused verbatim) plus the
  * entity-resolution features (decision 10). A feature that cannot be computed is omitted, never
  * defaulted.
  *
  * @param reliability
  *   the lowest source reliability (policy `sourceReliability`) among the sources of the facts the
  *   features read; dblp only gives the dblp value
  */
final case class PairEvidence(features: List[MatchFeature], reliability: Double) {
  private lazy val byName = features.map(f => f.name -> f.value).toMap
  def get(name: String): Option[Double] = byName.get(name)
}

object PairFeatures {
  import PairFeatureNames.*

  private def flag(b: Boolean): Double = if (b) 1.0 else 0.0

  /** Features of mentions `a` and `b` (indices into `corpus.mentions`), given the J06 features of
    * one of their candidates.
    */
  def compute(
      corpus: Corpus,
      policy: Policy,
      j06: List[MatchFeature],
      a: Int,
      b: Int
  ): PairEvidence = {
    val m = corpus.mentions
    val w = corpus.works
    val na = corpus.names.parts(m.name(a))
    val nb = corpus.names.parts(m.name(b))
    val wa = m.work(a)
    val wb = m.work(b)
    val out = List.newBuilder[MatchFeature]
    out ++= j06.filterNot(f => PairFeatureNames.ErComputed(f.name))
    out += MatchFeature(GivenNameRelation, NameParts.givenNameRelation(na, nb).toDouble)
    out += MatchFeature(SurnameEqual, flag(na.surname == nb.surname))
    if (na.folded.nonEmpty && nb.folded.nonEmpty)
      out += MatchFeature(NameSimilarity, round4(JaroWinkler.similarity(na.folded, nb.folded)))

    val teamA = w.authorNames(wa).length
    val teamB = w.authorNames(wb).length
    j06.find(_.name == CoauthorSharedCount).foreach { s =>
      val smaller = math.min(teamA, teamB) - 1
      if (smaller > 0)
        out += MatchFeature(CoauthorOverlapRatio, round4(math.min(1.0, s.value / smaller)))
    }
    for {
      va <- Option(w.venue(wa)).flatMap(policy.venueAreas.get)
      vb <- Option(w.venue(wb)).flatMap(policy.venueAreas.get)
    } out += MatchFeature(VenueAreaMatch, flag(va == vb))
    TitleText
      .jaccard(w.titleTokens(wa), w.titleTokens(wb))
      .foreach(j => out += MatchFeature(TitleSimilarity, round4(j)))
    if (teamA >= 2 && teamB >= 2) {
      def role(pos: Int, team: Int) = if (pos == 0) 1 else if (pos == team - 1) 2 else 0
      val ra = role(m.position(a), teamA)
      val rb = role(m.position(b), teamB)
      out += MatchFeature(AuthorRoleMatch, flag(ra != 0 && ra == rb))
    }

    var reliability = policy.sourceReliability("dblp")
    def use(sources: Iterable[String]): Unit =
      sources.foreach(s =>
        reliability = math.min(reliability, policy.sourceReliability.getOrElse(s, reliability))
      )

    val oa = m.orcids(a)
    val ob = m.orcids(b)
    if (oa.nonEmpty && ob.nonEmpty) {
      out += MatchFeature(OrcidMatch, flag((oa ++ ob).distinct.size == 1))
      use((m.linked(a, "orcid") ++ m.linked(b, "orcid")).map(_.sourceType))
    }
    val aa = m.linked(a, "openalex_author")
    val ab = m.linked(b, "openalex_author")
    if (aa.nonEmpty && ab.nonEmpty) {
      out += MatchFeature(
        OpenAlexAuthorMatch,
        flag((aa.map(_.value) ++ ab.map(_.value)).distinct.size == 1)
      )
      use((aa ++ ab).map(_.sourceType))
    }
    val ia = m.linked(a, "openalex_institution")
    val ib = m.linked(b, "openalex_institution")
    if (ia.nonEmpty && ib.nonEmpty) {
      out += MatchFeature(
        AffiliationOverlap,
        flag(ia.map(_.value).toSet.intersect(ib.map(_.value).toSet).nonEmpty)
      )
      use((ia ++ ib).map(_.sourceType))
    }
    out += MatchFeature(SourceReliability, reliability)
    PairEvidence(
      out.result().sortWith((x, y) => CodePointOrder.lessThan(x.name, y.name)),
      reliability
    )
  }

  /** Four decimals keep the written features stable and readable. */
  def round4(x: Double): Double = math.rint(x * 10000.0) / 10000.0
}
